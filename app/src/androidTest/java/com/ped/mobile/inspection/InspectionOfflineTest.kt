package com.ped.mobile.inspection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.network.ApiClient
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.util.UUID
import kotlin.concurrent.thread

/** Exercises real SQLite and real HTTP on the emulator, with a deterministic API. */
@RunWith(AndroidJUnit4::class)
class InspectionOfflineTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun savedVehiclesSurviveReopenAndKeepNullNoAndZeroDistinct() {
        val owner = "test-${UUID.randomUUID()}"
        val store = InspectionStore(context)
        val voyage = Voyage(7, "TEST VESSEL", "T01", false)
        val input = JSONObject().put("vehicle_make", "Toyota").put("chassis_number", "abc123")
            .put("key_count", 0).put("rear_camera", false).put("front_camera", true)
        val id = store.save(owner, voyage, input)
        store.close()
        val reopened = InspectionStore(context)
        val saved = reopened.list(owner).single()
        assertEquals(id, saved.id)
        assertEquals("ABC123", saved.data.getString("chassis_number"))
        assertEquals(0, saved.data.getInt("key_count"))
        assertTrue(saved.data.isNull("sensor_count"))
        assertFalse(saved.data.getBoolean("rear_camera"))
        assertTrue(saved.data.getBoolean("front_camera"))
        assertTrue(saved.data.isNull("gear_knob"))
        assertTrue(reopened.list("other-$owner").isEmpty())
        assertTrue(runCatching { reopened.save(owner, voyage, input) }.isFailure)
        reopened.save(owner, voyage, input.put("remarks", "Updated offline"), id)
        reopened.attempted(owner, id)
        assertTrue(runCatching { reopened.save(owner, voyage, input, id) }.isFailure)
        assertTrue(runCatching { reopened.deleteUnsent(owner, id) }.isFailure)
        assertEquals("Updated offline", reopened.list(owner).single().data.getString("remarks"))
        reopened.close()
    }
    @Test fun retriesAfterServerCommitDoNotDuplicateAndWrongAccountCannotSync() = runBlocking {
        FakeInspectionServer().use { server ->
            val repo = InspectionRepository(context, ApiClient(server.url), server.url)
            val account = repo.verifyAccount("test")
            val directory = repo.refresh("test", account)
            assertEquals(2, directory.voyages.size) // Follow pagination.
            val owner = repo.owner(account)
            val id = repo.store.save(owner, directory.voyages.first(), JSONObject().put("vehicle_make", "Toyota").put("chassis_number", "RETRY001"))
            server.failAfterCommit = true
            repo.sync("test", account) { _, _ -> }
            assertEquals("attempted", repo.store.list(owner).single().state)
            assertEquals(1, server.saved.size)
            repo.sync("test", account) { _, _ -> }
            assertEquals("synced", repo.store.list(owner).single().state)
            assertEquals(setOf(id), server.saved.keys)
            assertEquals(2, server.vehiclePosts)
            repo.store.save(owner, directory.voyages.first(), JSONObject().put("vehicle_make", "Honda").put("chassis_number", "OWNER002"))
            server.accountId = 99
            assertTrue(runCatching { repo.sync("test", account) { _, _ -> } }.isFailure)
            assertEquals(2, server.vehiclePosts)
            assertEquals(1, repo.store.list(owner).count { it.state == "pending" })
            repo.store.close()
        }
    }
    @Test fun permissionRevocationKeepsOfflineRecordsAndRefreshesCachedAccess() = runBlocking {
        FakeInspectionServer().use { server ->
            val repo = InspectionRepository(context, ApiClient(server.url), server.url)
            val account = repo.verifyAccount("permission-test")
            assertTrue(account.can("inspections.create"))
            val directory = repo.refresh("permission-test", account)
            val owner = repo.owner(account)
            repo.store.save(owner, directory.voyages.first(), JSONObject().put("vehicle_make", "Toyota").put("chassis_number", "PERMISSION001"))
            server.permissions = setOf("inspections.view")
            assertTrue(runCatching { repo.sync("permission-test", account) { _, _ -> } }.isFailure)
            assertEquals(0, server.vehiclePosts)
            assertEquals("PERMISSION001", repo.store.list(owner).single().data.getString("chassis_number"))
            assertNotEquals("synced", repo.store.list(owner).single().state)
            val cached = requireNotNull(repo.cachedAccount("permission-test"))
            assertFalse(cached.can("inspections.create"))
            assertTrue(cached.can("inspections.view"))
            server.permissions = setOf("inspections.view", "inspections.create")
            repo.sync("permission-test", account) { _, _ -> }
            assertEquals("synced", repo.store.list(owner).single().state)
            repo.store.close()
        }
    }
    @Test fun closedReportAndFailedRefreshPreserveLocalWork() = runBlocking {
        FakeInspectionServer().use { server ->
            val repo = InspectionRepository(context, ApiClient(server.url), server.url)
            val account = repo.verifyAccount("test")
            val directory = repo.refresh("test", account)
            val owner = repo.owner(account)
            repo.store.save(owner, directory.voyages.first(), JSONObject().put("vehicle_make", "Toyota").put("chassis_number", "CLOSED001"))
            server.closed = true
            repo.sync("test", account) { _, _ -> }
            val saved = repo.store.list(owner).single()
            assertEquals("attempted", saved.state)
            assertTrue(saved.error.contains("completed"))
            server.failDirectory = true
            assertTrue(runCatching { repo.refresh("test", account) }.isFailure)
            assertEquals(directory, repo.store.directory(owner))
            assertEquals("CLOSED001", repo.store.list(owner).single().data.getString("chassis_number"))
            repo.store.close()
        }
    }
}

internal class FakeInspectionServer : AutoCloseable {
    private val socket = ServerSocket(0)
    val url = "http://127.0.0.1:${socket.localPort}"
    @Volatile var permissions = setOf("ped.capture", "offdock.capture", "container.capture", "scrap.capture", "inspections.view", "inspections.create", "inspections.edit", "catalogue.view", "voyages.view")
    @Volatile var accountId = 7
    @Volatile var failAfterCommit = false
    @Volatile var failDirectory = false
    @Volatile var closed = false
    @Volatile var vehiclePosts = 0
    @Volatile var capturePosts = 0
    @Volatile var appRelease: JSONObject? = null
    @Volatile var appReleaseStatus = 200
    @Volatile var captureStatus = 200
    @Volatile var lastCapture = JSONObject()
    val saved = linkedMapOf<String, String>()
    private val worker = thread(isDaemon = true) {
        while (!socket.isClosed) {
            try { socket.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                val path = reader.readLine().split(" ")[1]
                var length = 0
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    if (line.startsWith("Content-Length:", true)) length = line.substringAfter(':').trim().toInt()
                }
                val chars = CharArray(length); var offset = 0
                while (offset < length) { val count = reader.read(chars, offset, length - offset); if (count < 0) break; offset += count }
                val payload = if (length > 0) JSONObject(String(chars)) else JSONObject()
                var status = 200
                val data = when {
                    path == "/app-release" -> { status = appReleaseStatus; appRelease ?: JSONObject.NULL }
                    path == "/login" -> JSONObject().put("token", "ui-token")
                    path.startsWith("/ped-save") -> { capturePosts++; lastCapture = payload; status = captureStatus; JSONObject().put("id", 42) }

                    path == "/profile" -> JSONObject().put("id", accountId).put("email", "inspector@test.local").put("permissions", JSONArray(permissions.toList()))
                    path.startsWith("/inspection-voyages") || path.startsWith("/ped-voyages") -> {
                        if (failDirectory) status = 503
                        val page = path.substringAfter("page=", "1").toInt()
                        JSONObject().put("data", JSONArray().put(Voyage(page.toLong(), "TEST VESSEL", "T0$page", false).json())).put("last_page", 2)
                    }
                    path.startsWith("/vehicle-catalogue/sync") -> {
                        val cursor = path.substringAfter("cursor=", "0").substringBefore('&').toLong()
                        val rows = JSONArray()
                        if (cursor < 1) rows.put(JSONObject().put("id", 1).put("make", "Toyota").put("model", "Corolla").put("active", true).put("revision", 1))
                        JSONObject().put("catalogue_id", "test-catalogue").put("data", rows).put("next_cursor", 1).put("has_more", false)
                    }
                    path == "/inspection-options" -> JSONObject().put("vehicle_makes", JSONArray(listOf("Toyota", "Honda")))
                    path == "/inspection-reports" && "inspections.create" !in permissions -> { status = 403; JSONObject() }
                    path.endsWith("/vehicles") -> {
                        vehiclePosts++
                        val id = payload.getString("submission_id")
                        if (closed && !saved.containsKey(id)) status = 409
                        else saved[id] = payload.toString()
                        if (failAfterCommit) { status = 503; failAfterCommit = false }
                        JSONObject().put("vehicle", JSONObject().put("submission_id", id))
                    }
                    else -> JSONObject().put("id", 1).put("report_number", "VIR-TEST-001").put("status", if (closed) "completed" else "draft")
                }
                val body = JSONObject().put("success", status == 200).put("data", data).put("message", if (closed) "Report completed; cannot add vehicles." else "Temporary failure. Retry.").toString().toByteArray()
                client.getOutputStream().apply {
                    write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(body); flush()
                }
            } } catch (_: Exception) { if (socket.isClosed) break }
        }
    }
    override fun close() { socket.close(); worker.join(1000) }
}
