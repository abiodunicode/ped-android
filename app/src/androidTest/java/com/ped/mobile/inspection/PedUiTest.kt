package com.ped.mobile.inspection

import android.content.Intent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.PedHarnessActivity
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PedUiTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun signInDraftRotationValidationCaptureAndUpload() {
        FakeInspectionServer().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            ActivityScenario.launch<PedHarnessActivity>(Intent(context, PedHarnessActivity::class.java).putExtra("apiBase", server.url)).use { activity ->
                compose.onNodeWithText("Email address").performScrollTo().assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
                compose.onNodeWithText("Password").performScrollTo().performTextClearance()
                compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
                compose.onNodeWithText("Enter your email address.").assertExists()
                compose.onNodeWithText("Enter your password.").assertExists()
                compose.onNodeWithText("Email address").performScrollTo().performTextInput("inspector@test.local")
                compose.onNodeWithText("Password").performScrollTo().performTextInput("test-password")
                compose.onNodeWithContentDescription("Show password").performClick()
                compose.onNodeWithContentDescription("Hide password").assertExists()
                screenshot("ped-login.png")
                compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
                waitFor("Capture a record")
                compose.onNodeWithText("Scan chassis from photo").performScrollTo().assertIsDisplayed().assertHasClickAction()
                compose.onNodeWithText("Read VIN / serial").assertDoesNotExist()
                compose.onNodeWithText("Scan barcode").assertDoesNotExist()
                compose.waitUntil(10000) { compose.onAllNodes(hasText("Chassis number *") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Chassis number *").performScrollTo().performTextInput("DRAFT001")
                compose.onNodeWithContentDescription("Open navigation").performClick()
                screenshot("ped-sidebar.png")
                compose.onNode(hasText("Uploads") and hasClickAction()).performScrollTo().performClick()
                compose.onNodeWithContentDescription("Open navigation").performClick()
                compose.onNode(hasText("Capture Evidence") and hasClickAction()).performScrollTo().performClick()
                waitFor("Capture a record")
                activity.recreate()
                waitFor("Capture a record")
                compose.onNodeWithText("Chassis number *").performScrollTo().assertTextContains("DRAFT001")
                compose.onNodeWithText("Save on device").performScrollTo().performClick()
                compose.onNodeWithText("Add driver face before saving.").assertExists()
                compose.onNodeWithText("Chassis number *").performScrollTo().assertTextContains("DRAFT001")
                compose.onNodeWithText("Scrap").performScrollTo().performClick()
                compose.onNodeWithText("Change record type?").assertExists()
                compose.onNodeWithText("Keep editing").performClick()
                compose.onNodeWithText("Chassis number *").assertTextContains("DRAFT001")
                compose.onNodeWithText("Scrap").performScrollTo().performClick()
                compose.onNodeWithText("Change type").performClick()
                compose.onNodeWithText("Chassis number *").performScrollTo().performTextInput("FIELD001")
                screenshot("ped-capture.png")
                compose.onNodeWithText("Save on device").performScrollTo().performClick()
                waitFor("Capture saved on this device. Open Uploads when you have internet.")
                compose.onNodeWithText("View uploads").performScrollTo().performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("FIELD001", substring = true))
                waitFor("FIELD001")
                screenshot("ped-uploads.png")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Upload all (1)"))
                server.captureStatus = 503
                compose.onNodeWithText("Upload all (1)").performClick()
                waitFor("Failed (1)")
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                compose.onNodeWithText("Failed (1)").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Failed · needs attention"))
                server.captureStatus = 200
                compose.onNodeWithText("Retry upload").performScrollTo().performClick()
                waitFor("Everything saved for this account has been uploaded. Open Uploaded to review confirmations.")
                assertEquals(2, server.capturePosts)
                compose.onNode(hasScrollToIndexAction()).performScrollToIndex(0)
                compose.onNodeWithText("Uploaded (1)").performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Server reference: 42"))
                compose.onNodeWithText("Server reference: 42").assertExists()
                compose.onNodeWithContentDescription("Open navigation").performClick()
                compose.onNode(hasText("Account & Settings") and hasClickAction()).performScrollTo().performClick()
                compose.onNodeWithText("Sign out").performScrollTo().performClick()
                compose.onNodeWithText("Stay signed in").performClick()
                compose.onNodeWithText("Sign out").performClick()
                compose.onAllNodesWithText("Sign out").onLast().performClick()
                waitFor("Sign in")
            }
        }
    }
    @Test fun tallyDraftSurvivesSidebarAndUploadsThroughSharedQueue() {
        FakeInspectionServer().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            com.ped.mobile.data.SessionStore(context).clearToken()
            ActivityScenario.launch<PedHarnessActivity>(Intent(context, PedHarnessActivity::class.java).putExtra("apiBase", server.url)).use { activity ->
                compose.onNodeWithText("Email address").performScrollTo().performTextReplacement("inspector@test.local")
                compose.onNodeWithText("Password").performScrollTo().performTextReplacement("test-password")
                compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
                waitFor("Capture a record")
                navigate("Inspection Tally")
                waitFor("Choose voyage · capture · review")
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Prepare offline capture"))
                compose.onNodeWithText("Prepare offline capture").performClick()
                compose.waitUntil(10000) {
                    runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Start tally")); true }.getOrDefault(false)
                }
                compose.onAllNodesWithText("Start tally")[0].performClick()
                compose.onNodeWithText("Vehicle make *").performScrollTo().performClick()
                compose.onNodeWithText("General Cargo", useUnmergedTree = true).performScrollTo().performClick()
                compose.onNodeWithText("Chassis number *").performScrollTo().performTextInput("SIDEBAR001")
                navigate("Uploads")
                waitFor("Upload all (0)")
                navigate("Inspection Tally")
                waitFor("Chassis number *")
                compose.onNodeWithText("Chassis number *").performScrollTo().assertTextContains("SIDEBAR001")
                activity.recreate()
                waitFor("Chassis number *")
                compose.onNodeWithText("Chassis number *").performScrollTo().assertTextContains("SIDEBAR001")
                compose.onNodeWithText("Save & review tally").performScrollTo().performClick()
                compose.waitUntil(10000) {
                    runCatching { compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("View uploads (1)")); true }.getOrDefault(false)
                }
                compose.onNodeWithText("View uploads (1)").performClick()
                waitFor("Upload all (1)")
                compose.onNodeWithText("Upload all (1)").performScrollTo().performClick()
                waitFor("Uploaded (1)")
                compose.onNodeWithText("Uploaded (1)").performScrollTo().performClick()
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Uploaded tally · VIR-TEST-001"))
                assertEquals(1, server.vehiclePosts)
            }
        }
    }

    private fun navigate(label: String) {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNode(hasText(label) and hasClickAction()).performScrollTo().performClick()
    }
    private fun waitFor(text: String) {
        try {
            compose.waitUntil(20000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        } catch (error: Throwable) {
            screenshot("ped-failure.png")
            throw AssertionError("Waiting for $text\n${compose.onRoot().printToString()}", error)
        }
    }
    private fun screenshot(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { File(it) } ?: context.getExternalFilesDir(null)!!
        output.mkdirs()
        File(output, name).outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }
}
