package com.ped.mobile.inspection

import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.data.*
import com.ped.mobile.network.ApiClient
import com.ped.mobile.sync.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class AutoSyncTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() { SyncSettings(context).enabled = false; AutoSync.deactivate(context) }
    @After fun cleanup() { AutoSync.deactivate(context); SyncSettings(context).enabled = true; SyncSettings(context).wifiOnly = false }
    private fun capture(queue: QueueStore, kind: CaptureKind = CaptureKind.PED) = PendingCapture(queue.newCaptureId(), kind, "/ped-save", "Evidence test", "2026-10-08T10:00:00Z", mapOf("chassis" to "AUTO123"))
    private fun tally(repo: InspectionRepository, owner: String) = repo.store.save(owner, Voyage(1, "SHIP", "V01", false), JSONObject().put("vehicle_make", "Toyota").put("chassis_number", "AUTO123"))

    @Test fun bothQueuesSyncAndConcurrentManualRunDoesNotResend() = runBlocking {
        FakeInspectionServer().use { server ->
            val repo = InspectionRepository.get(context, server.url)
            val owner = repo.owner(repo.verifyAccount("auto-test"))
            val queue = QueueStore(context, owner)
            queue.add(capture(queue)); tally(repo, owner)
            coroutineScope {
                listOf(true, false).map { auto -> async(Dispatchers.IO) { SyncCoordinator.run(context, server.url, "auto-test", owner, auto) } }.awaitAll()
            }
            assertEquals(1, server.capturePosts)
            assertEquals(1, server.vehiclePosts)
            assertTrue(queue.loadQueue().isEmpty())
            assertEquals(1, queue.history().size)
            assertEquals("synced", repo.store.list(owner).single().state)
            queue.close()
        }
    }
    @Test fun uncertainEvidenceStaysLocalUntilManualRetry() = runBlocking {
        FakeInspectionServer().use { server ->
            val repo = InspectionRepository.get(context, server.url)
            val owner = repo.owner(repo.verifyAccount("auto-test"))
            val queue = QueueStore(context, owner)
            queue.add(capture(queue)); server.captureStatus = 503
            SyncCoordinator.run(context, server.url, "auto-test", owner, true)
            assertEquals(1, server.capturePosts)
            assertTrue(queue.loadQueue().single().error.contains("not confirmed"))
            server.captureStatus = 200
            SyncCoordinator.run(context, server.url, "auto-test", owner, true)
            assertEquals(1, server.capturePosts)
            SyncCoordinator.run(context, server.url, "auto-test", owner, false)
            assertEquals(2, server.capturePosts)
            assertTrue(queue.loadQueue().isEmpty())
            queue.close()
        }
    }
    @Test fun permissionsAndOwnerAreVerifiedBeforeAutoUpload() = runBlocking {
        FakeInspectionServer().use { server ->
            val repo = InspectionRepository.get(context, server.url)
            val owner = repo.owner(repo.verifyAccount("auto-test"))
            val queue = QueueStore(context, owner)
            queue.add(capture(queue)); tally(repo, owner)
            server.permissions = setOf("inspections.view", "inspections.create")
            SyncCoordinator.run(context, server.url, "auto-test", owner, true)
            assertEquals(0, server.capturePosts); assertEquals(1, server.vehiclePosts)
            assertEquals(1, queue.loadQueue().size)
            server.permissions = setOf("ped.capture"); server.accountId = 99
            SyncCoordinator.run(context, server.url, "auto-test", owner, true)
            assertEquals(0, server.capturePosts)
            assertEquals(1, queue.loadQueue().size)
            queue.close()
        }
    }
    @Test fun tallyResponseLossRetriesUsingSameSubmissionId() = runBlocking {
        FakeInspectionServer().use { server ->
            val repo = InspectionRepository.get(context, server.url)
            val owner = repo.owner(repo.verifyAccount("auto-test"))
            val id = tally(repo, owner)
            server.failAfterCommit = true
            assertTrue(SyncCoordinator.run(context, server.url, "auto-test", owner, true).retry)
            assertEquals("attempted", repo.store.list(owner).single().state)
            SyncCoordinator.run(context, server.url, "auto-test", owner, true)
            assertEquals(setOf(id), server.saved.keys)
            assertEquals("synced", repo.store.list(owner).single().state)
        }
    }
    @Test fun workerUploadsAutomaticallyAfterConnectivityReturns() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use { descriptor -> java.io.FileInputStream(descriptor.fileDescriptor).use { it.readBytes() } } }
        FakeInspectionServer().use { server ->
            val repo = InspectionRepository.get(context, server.url)
            val owner = repo.owner(repo.verifyAccount("worker-test"))
            val queue = QueueStore(context, owner)
            queue.add(capture(queue))
            try {
                shell("svc wifi disable"); shell("svc data disable")
                delay(1500)
                SyncSettings(context).enabled = true
                AutoSync.activate(context, server.url, owner, "worker-test")
                delay(2000)
                assertEquals(0, server.capturePosts)
                shell("svc wifi enable"); shell("svc data enable")
                withTimeout(60000) { while (queue.loadQueue().isNotEmpty()) delay(500) }
                assertEquals(1, server.capturePosts)
                assertEquals(1, queue.history().size)
            } finally { shell("svc wifi enable"); shell("svc data enable"); queue.close() }
        }
    }
}
