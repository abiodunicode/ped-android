package com.ped.mobile.inspection

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.PedWorkspace
import com.ped.mobile.data.AppScreen
import com.ped.mobile.ui.PedTheme
import org.junit.Rule
import org.junit.Test
import java.io.File

class WorkspaceAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun drawerSupportsLargeTextAndBackDismissesWithoutLeavingCapture() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                PedTheme(darkTheme = true) {
                    PedWorkspace(AppScreen.CAPTURE, InspectionAccount(1, "inspector@ped.local", setOf("ped.capture", "inspections.view")), false, 12, false, {}) {
                        Text("Capture content", Modifier.padding(it))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNode(hasText("Capture Evidence") and hasClickAction()).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        screenshot("sidebar-dark-large-text.png")
        compose.onNode(hasText("Inspection Tally") and hasClickAction()).performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Account & Settings") and hasClickAction()).performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        Espresso.pressBack()
        compose.onNodeWithText("Capture content").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithContentDescription("Close navigation").performScrollTo().performClick()
        compose.onNodeWithText("Capture content").assertIsDisplayed()
    }

    @Test fun wideLayoutKeepsSidebarVisibleAndNavigates() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(.8f, 1f)) {
                PedTheme {
                    var destination by remember { mutableStateOf(AppScreen.CAPTURE) }
                    PedWorkspace(destination, InspectionAccount(1, "inspector@ped.local", setOf("ped.capture", "inspections.view")), true, 2, false, { destination = it }) {
                        Text("Content for $destination", Modifier.padding(it))
                    }
                }
            }
        }
        compose.onNodeWithContentDescription("Open navigation").assertDoesNotExist()
        compose.onNode(hasText("Uploads") and hasClickAction()).performClick()
        compose.onNodeWithText("Content for SYNC").assertIsDisplayed()
        screenshot("sidebar-wide.png")
    }

    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) } ?: context.getExternalFilesDir(null)!!
        output.mkdirs()
        File(output, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
