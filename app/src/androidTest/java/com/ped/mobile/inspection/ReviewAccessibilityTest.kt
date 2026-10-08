package com.ped.mobile.inspection

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.ui.PedTheme
import com.ped.mobile.PhotoReview
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ReviewAccessibilityTest {
    @get:Rule val compose = createComposeRule()
    @Test fun savedInspectionReadableAtDoubleTextScaleInDarkMode() {
        val form = JSONObject().put("vehicle_make", "Toyota").put("chassis_number", "VIN123456789012345")
            .put("key_count", 0).put("front_camera", false).put("rear_camera", true).put("remarks", "Check rear bumper before discharge.")
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PedTheme(darkTheme = true) { SavedVehicleSummary(Voyage(1, "TEST VESSEL", "T01", false), form, null, {}, {}) }
            }
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("None (0)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("No · Not observed").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Yes · Present").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check rear bumper before discharge.").performScrollTo().assertIsDisplayed()
        screenshot("summary-dark-large-text.png")
    }
    @Test fun photoReviewHasAccessibleZoomAndCloseActions() {
        compose.setContent { PedTheme { PhotoReview("Chassis photo", "") {} } }
        compose.onNodeWithText("Zoom in").performClick()
        compose.onNodeWithText("Zoom out").performClick()
        compose.onNodeWithText("Reset").performClick()
        compose.onNodeWithText("Photo unavailable. Close and retake the photo if needed.").assertExists()
        compose.onNodeWithText("Close").assertHasClickAction()
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir(null), name)
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
