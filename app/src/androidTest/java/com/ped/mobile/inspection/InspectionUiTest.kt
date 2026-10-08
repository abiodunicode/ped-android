package com.ped.mobile.inspection

import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class InspectionUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun prepareCaptureAndReviewSavedTally() {
        FakeInspectionServer().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ActivityScenario.launch<InspectionHarnessActivity>(Intent(context, InspectionHarnessActivity::class.java).putExtra("apiBase", server.url)).use { scenario ->
            compose.waitUntil(10000) { compose.onAllNodes(hasText("Prepare offline capture") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Prepare offline capture").performScrollTo().performClick()
            awaitInList("Start tally")
            compose.onAllNodesWithText("Start tally")[0].performScrollTo().performClick()
            compose.onNodeWithText("Save & review tally").assertIsNotDisplayed()
            compose.onNodeWithText("Vehicle make *").performScrollTo().performClick()
            compose.onNodeWithText("General Cargo", useUnmergedTree = true).performScrollTo().performClick()
            // Physical backspace must reach the editable field while suggestions are open.
            compose.onNodeWithText("Vehicle make *").performScrollTo().performClick()
            compose.onNodeWithText("Vehicle make *").performTextInputSelection(androidx.compose.ui.text.TextRange(13))
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DEL)
            compose.onNodeWithText("Vehicle make *").assertTextContains("General Carg", substring = false)
            compose.onNodeWithText("Vehicle make *").performTextClearance()
            compose.onNodeWithText("Vehicle make *").performTextInput("General")
            compose.onNodeWithText("General Cargo", useUnmergedTree = true).performScrollTo().performClick()
            compose.onNodeWithText("Use dropdown").performScrollTo().performClick()
            compose.onNodeWithText("Use dropdown").assertIsOff()
            compose.onAllNodesWithText("Vehicle make *").assertCountEquals(1)
            compose.onNodeWithText("Vehicle make *").assertTextContains("General Cargo")
            scenario.recreate()
            compose.waitUntil(10000) { compose.onAllNodesWithText("Use dropdown").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Use dropdown").assertIsOff()
            compose.onNodeWithText("Vehicle make *").performScrollTo().assertTextContains("General Cargo")
            compose.onNodeWithText("Vehicle make *").performTextClearance()
            compose.onNodeWithText("Vehicle make *").performTextInput("Toyota")
            compose.waitUntil(10000) { compose.onAllNodesWithText("Toyota Corolla").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Toyota Corolla").performScrollTo().performClick()
            compose.onNodeWithText("Use dropdown").performScrollTo().performClick()
            compose.onNodeWithText("Use dropdown").assertIsOn()
            compose.onAllNodesWithText("Vehicle make *").assertCountEquals(1)
            compose.onNodeWithText("Vehicle make *").assertTextContains("Toyota Corolla")
            compose.onNodeWithText("Chassis number *").performScrollTo().performTextInput("MOBILE001")
            screenshot("tally-form.png")
            compose.onNodeWithText("Save & review tally").assertIsNotDisplayed()
            compose.onNodeWithText("Save & review tally").performScrollTo().performClick()
            awaitInList("Saved on device · not sent")
            compose.onNodeWithText("Saved on device · not sent").assertExists()
            assertEquals(0, server.vehiclePosts)
            compose.onNodeWithText("Sync 1 saved vehicle").performScrollTo()
            screenshot("tally-saved.png")
            compose.onNodeWithText("Sync 1 saved vehicle").performClick()
            awaitInList("Uploaded · VIR-TEST-001")
            compose.onNodeWithText("Uploaded · VIR-TEST-001").assertExists()
            compose.onNodeWithText("View vehicle").performScrollTo().performClick()
            compose.onNodeWithText("Exterior & visibility").assertExists()
            compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            compose.onAllNodesWithText("Save & review tally").assertCountEquals(0)
            }
        }
    }
    @Test fun saveNextReturnsToIdentityAndKeepsButtonsBelowTheForm() {
        FakeInspectionServer().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ActivityScenario.launch<InspectionHarnessActivity>(Intent(context, InspectionHarnessActivity::class.java).putExtra("apiBase", server.url)).use {
                compose.waitUntil(10000) { compose.onAllNodes(hasText("Prepare offline capture") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Prepare offline capture").performScrollTo().performClick()
                awaitInList("Start tally")
                compose.onAllNodesWithText("Start tally")[0].performScrollTo().performClick()
                compose.onNodeWithText("Vehicle make *").performScrollTo().performClick()
                compose.onNodeWithText("General Cargo", useUnmergedTree = true).performScrollTo().performClick()
                compose.onNodeWithText("Chassis number *").performScrollTo().performTextInput("NEXT001")
                compose.onNodeWithText("Save & next vehicle").performScrollTo().performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("Vehicle saved on this device. Sync it when you have internet.").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Vehicle make *").assertIsDisplayed().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                compose.onNodeWithText("Save & next vehicle").assertIsNotDisplayed()
                compose.onNodeWithText("General Cargo").assertDoesNotExist()
                compose.onNodeWithContentDescription("Back to tally").performClick()
                compose.onNodeWithText("Saved tally (1)").performClick()
                awaitInList("Saved on device · not sent")
                assertEquals(0, server.vehiclePosts)
            }
        }
    }
    private fun awaitInList(text: String) {
        var lastFailure: Throwable? = null
        try {
            compose.waitUntil(15000) {
                runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text)); true }
                    .onFailure { lastFailure = it }.getOrDefault(false)
            }
        } catch (error: Throwable) {
            screenshot("tally-failure.png")
            throw AssertionError("Waiting for $text: $lastFailure\n${compose.onRoot().printToString()}", error)
        }
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) } ?: context.getExternalFilesDir(null)!!
        output.mkdirs()
        File(output, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
