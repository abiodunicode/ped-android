package com.ped.mobile.inspection

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.PedVoyageCache
import com.ped.mobile.PedVoyagePicker
import com.ped.mobile.data.*
import com.ped.mobile.network.ApiClient
import com.ped.mobile.ui.PedTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class PedVoyageTest {
    @get:Rule val compose = createComposeRule()
    @Test fun offlinePickerKeepsSavedQueueSnapshotAndAccountIsolation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "voyage-test-${UUID.randomUUID()}"
        FakeInspectionServer().use { server ->
            val cache = PedVoyageCache(context, owner)
            val voyages = cache.refresh(server.url, "token")
            assertEquals(2, voyages.size)
            cache.select(voyages.first())
            assertTrue(PedVoyageCache(context, "$owner-other").read().isEmpty())
            val values = mutableStateMapOf<String, String>()
            compose.setContent { PedTheme { PedVoyagePicker(owner, "token", server.url, false, true, values) { key, value -> values[key] = value } } }
            compose.waitUntil(5000) { values["inspection_voyage_id"] == "1" }
            val queue = QueueStore(context, owner)
            val item = PendingCapture(UUID.randomUUID().toString(), CaptureKind.PED, "/ped-save", "PED: VIN123", "2026-10-05T12:00:00Z", values.toMap() + ("chassis" to "VIN123"))
            queue.add(item)
            compose.onNodeWithText("Change voyage").performClick()
            compose.onNode(hasText("TEST VESSEL") and hasClickAction()).performClick()
            compose.onNodeWithText("Voyage T02").performClick()
            compose.waitUntil(5000) { values["inspection_voyage_id"] == "2" }
            val reopened = QueueStore(context, owner)
            val pending = reopened.loadCaptureForSync(item.id)!!
            assertEquals("1", pending.payload["inspection_voyage_id"])
            assertEquals("T01", pending.payload["voyage_number"])
            ApiClient(server.url).syncCapture("token", pending).getOrThrow()
            assertEquals("1", server.lastCapture.optString("inspection_voyage_id"))
            queue.clear()
        }
    }
}
