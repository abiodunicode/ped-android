package com.ped.mobile.inspection

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.scanning.*
import com.ped.mobile.ui.PedTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CargoReferenceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bundledDatabaseWorksOfflineAndNeverConflatesSimilarNumbers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reference = CargoReference.get(context)
        val vin = "SALME15406A217483"
        assertTrue(vin in reference.exact(listOf(vin)))
        assertEquals(emptySet<String>(), reference.exact(listOf("UNLISTED123456789")))
        val read = vin.replace('0', 'O')
        assertFalse(read in reference.exact(listOf(read)))
        assertTrue(vin in reference.similar(read))
        assertEquals(emptyList<String>(), reference.similar("123456"))
        assertTrue(matchChassis("CARGO 3906208000111416076\n$vin", reference)!!.exact)
    }

    @Test fun suggestedCorrectionRequiresExplicitSelectionAndConfirmation() {
        var confirmed: String? = null
        val vin = "SALME15406A217483"
        val read = vin.replace('0', 'O')
        compose.setContent {
            var number by remember { mutableStateOf(read) }
            PedTheme { ChassisMatchDialog(number, read, emptyList(), true, { number = it }, { confirmed = it }, {}) }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Possible match · check the label").fetchSemanticsNodes().isNotEmpty() }
        assertNull(confirmed)
        screenshot("cargo-reference-review.png")
        compose.onNodeWithText("Chassis number to use").assertTextContains(read)
        compose.onNodeWithText("Compare $vin").performScrollTo().performClick()
        assertNull(confirmed)
        compose.waitUntil(10000) { compose.onAllNodesWithText("Exact match in Cargo ID reference").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Use this number").performClick()
        assertEquals(vin, confirmed)
    }

    @Test fun onDevicePhotoRecognitionFindsCargoIdAmongOtherNumbers() {
        val bitmap = android.graphics.Bitmap.createBitmap(1800, 700, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.BLACK
            textSize = 64f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        canvas.drawText("BOOKING: 3906208000111416076", 40f, 110f, paint)
        canvas.drawText("SALME15406A217483", 40f, 290f, paint)
        canvas.drawText("DATE: 2026-10-05", 40f, 460f, paint)
        val recognizer = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
        try {
            val text = com.google.android.gms.tasks.Tasks.await(
                recognizer.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0)), 30, java.util.concurrent.TimeUnit.SECONDS
            ).text
            val ref = CargoReference.get(InstrumentationRegistry.getInstrumentation().targetContext)
            assertEquals("SALME15406A217483", matchChassis(text, ref)!!.number)
        } finally { recognizer.close(); bitmap.recycle() }
    }

    @Test fun darkLargeTextDialogKeepsCorrectionAndDiscardAccessible() {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, 2f)) {
                PedTheme(darkTheme = true) {
                    ChassisMatchDialog("SALME154O6A217483", "SALME154O6A217483", emptyList(), true, {}, {}, {})
                }
            }
        }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Possible match · check the label").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Compare SALME15406A217483").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Discard scan").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Use this number").assertIsDisplayed()
        screenshot("cargo-reference-dark-large.png")
    }

    @Test fun unlistedChassisCanStillBeConfirmed() {
        var confirmed: String? = null
        compose.setContent { PedTheme { ChassisMatchDialog("NEW12345", "NEW12345", emptyList(), true, {}, { confirmed = it }, {}) } }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Not in reference").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Use this number").performClick()
        assertEquals("NEW12345", confirmed)
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { java.io.File(it) } ?: context.getExternalFilesDir(null)!!
        output.mkdirs()
        java.io.File(output, name).outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

}
