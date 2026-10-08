package com.ped.mobile.inspection

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.ped.mobile.PedHarnessActivity
import org.junit.Rule
import org.junit.Test

class WorkflowLoginTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun captureClerkCannotNavigateToTally() = checkRole(false)
    @Test fun tallyClerkStartsInTallyAndCannotNavigateToEvidence() = checkRole(true)

    private fun checkRole(tally: Boolean) {
        FakeInspectionServer().use { server ->
            server.permissions = if (tally) setOf("inspections.view", "inspections.create", "voyages.view", "catalogue.view")
                else setOf("ped.capture", "offdock.capture", "container.capture", "scrap.capture", "catalogue.view")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Test emulator only: isolate the login/session state between test users.
            context.getSharedPreferences("ped-session", 0).edit().clear().commit()
            ActivityScenario.launch<PedHarnessActivity>(Intent(context, PedHarnessActivity::class.java).putExtra("apiBase", server.url)).use { scenario ->
                compose.onNodeWithText("Email address").performScrollTo().performTextInput("clerk@test.local")
                compose.onNodeWithText("Password").performScrollTo().performTextInput("password")
                compose.onNode(hasText("Sign in") and hasClickAction()).performScrollTo().performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithContentDescription("Open navigation").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Open navigation").performClick()
                compose.onNode(hasText(if (tally) "Inspection Tally" else "Capture Evidence") and hasClickAction()).assertExists()
                compose.onNode(hasText(if (tally) "Capture Evidence" else "Inspection Tally") and hasClickAction()).assertDoesNotExist()
                compose.onNodeWithText(if (tally) "Inspection tally only" else "Picture evidence only").assertExists()
                compose.onNode(hasText("App updates") and hasClickAction()).assertExists()
                compose.onNode(hasText("Uploads") and hasClickAction()).performClick()
                compose.onNodeWithContentDescription("Automatic sync").assertExists()
                compose.onNodeWithContentDescription("Wi-Fi only automatic sync").assertExists()
                compose.onNode(hasText("Upload all (0)") and hasClickAction()).assertExists()
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithContentDescription("Open navigation").performClick()
                compose.onNode(hasText(if (tally) "Capture Evidence" else "Inspection Tally") and hasClickAction()).assertDoesNotExist()
            }
        }
    }
}
