package com.ped.mobile

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.data.CaptureKind
import com.ped.mobile.data.PendingCapture
import com.ped.mobile.data.QueueStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PhotoDecodeTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun freshlyCapturedJpegCanBePreviewedWithoutChangingEvidence() {
        val source = jpegDataUrl(1600, 900)
        val preview = source.decodeBitmap()
        assertNotNull("A valid captured JPEG must have a preview", preview)
        preview!!
        try {
            assertTrue(preview.width in 1..900)
            assertEquals(preview.width / 16.0, preview.height / 9.0, 0.01)
        } finally { preview.recycle() }
    }

    @Test fun persistedQueuePhotoCanBePreviewedWithoutDeletingTheFile() {
        val owner = "photo-test-${UUID.randomUUID()}"
        val queue = QueueStore(context, owner)
        val id = queue.newCaptureId()
        try {
            queue.add(PendingCapture(id, CaptureKind.PED, "/ped-save", "Photo preview test",
                "2026-10-09T12:00:00Z", mapOf("driver_face" to jpegDataUrl(2400, 1200))))
            val stored = queue.loadCaptureForSync(id)!!.payload.getValue("driver_face")
            assertTrue(stored.startsWith("pedfile:"))
            val file = File(stored.removePrefix("pedfile:"))
            val original = file.readBytes()
            val preview = stored.decodeBitmap()
            assertNotNull("A saved evidence file must have a preview", preview)
            preview!!
            try {
                assertTrue(preview.width in 1..1800)
                assertEquals(preview.width, preview.height * 2)
                assertArrayEquals("Viewing evidence must not change the original", original, file.readBytes())
            } finally { preview.recycle() }
        } finally {
            queue.remove(id)
            queue.close()
            context.deleteDatabase("ped_queue_$owner.db")
        }
    }

    @Test fun unavailableOrCorruptSourcesReturnNoPreview() {
        assertNull("".decodeBitmap())
        assertNull("data:image/jpeg;base64,not-an-image".decodeBitmap())
        assertNull("pedfile:${context.cacheDir}/missing-${UUID.randomUUID()}.jpg".decodeBitmap())
        val corrupt = File.createTempFile("invalid-photo-", ".jpg", context.cacheDir)
        try {
            corrupt.writeText("This is not an image")
            assertNull("pedfile:${corrupt.absolutePath}".decodeBitmap())
        } finally { corrupt.delete() }
    }
}

internal fun jpegDataUrl(width: Int = 80, height: Int = 60): String {
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        bitmap.eraseColor(Color.BLUE)
        val bytes = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, this) }.toByteArray()
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    } finally { bitmap.recycle() }
}
