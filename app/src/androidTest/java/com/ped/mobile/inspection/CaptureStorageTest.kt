package com.ped.mobile.inspection

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CaptureStorageTest {
    @Test fun accountIsolationDraftRecoveryAndConfirmationHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val owner = "test-${UUID.randomUUID()}"
        val first = QueueStore(context, owner)
        val other = QueueStore(context, "$owner-other")
        val item = PendingCapture(UUID.randomUUID().toString(), CaptureKind.SCRAP, "/ped-save-scrap", "Scrap: ABC123", "2026-10-03T12:00:00Z", mapOf("chassis" to "ABC123"))
        first.add(item)
        first.saveDraft(CaptureKind.PED, mapOf("chassis" to "DRAFT123"))
        assertTrue(other.loadQueue().isEmpty())
        assertNull(other.loadCaptureForSync(item.id))
        assertNull(other.readDraft())
        val reopened = QueueStore(context, owner)
        assertEquals("DRAFT123", reopened.readDraft()!!.second["chassis"])
        reopened.failed(item.id, "Reconnect and retry")
        assertEquals("Reconnect and retry", reopened.loadQueue().single().error)
        reopened.uploaded(item, "42")
        assertTrue(reopened.loadQueue().isEmpty())
        assertNull(reopened.loadCaptureForSync(item.id))
        assertEquals("42", QueueStore(context, owner).history().single().reference)
        assertTrue(other.history().isEmpty())
        reopened.clearDraft()
        assertNull(reopened.readDraft())
    }
}
