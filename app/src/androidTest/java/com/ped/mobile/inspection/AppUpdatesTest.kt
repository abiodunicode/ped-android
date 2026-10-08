package com.ped.mobile.inspection

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.ped.mobile.AppUpdatesScreen
import com.ped.mobile.BuildConfig
import com.ped.mobile.ui.PedTheme
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test

class AppUpdatesTest {
    @get:Rule val compose = createComposeRule()
    private fun waitFor(text: String) = compose.waitUntil(10000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun refresh() = compose.onNodeWithText("Check for updates").performScrollTo().performClick()
    private fun release(code: Int) = JSONObject().put("id", "a".repeat(32))
        .put("version_name", "2.0.0").put("version_code", code)
        .put("size_bytes", 60000000).put("notes", "Improved capture")

    @Test fun checksPublishedBuildAndClearsStaleDownloadOnFailure() {
        FakeInspectionServer().use { server ->
            compose.setContent { PedTheme { AppUpdatesScreen(apiBase = server.url, online = true) } }
            waitFor("No app release has been published yet.")
            compose.onNodeWithText("Download update").assertDoesNotExist()
            server.appRelease = release(BuildConfig.VERSION_CODE + 1)
            refresh()
            waitFor("Update available")
            compose.onNodeWithText("Download update").assertIsEnabled()
            server.appRelease = release(BuildConfig.VERSION_CODE)
            refresh()
            waitFor("You’re up to date")
            compose.onNodeWithText("Download update").assertDoesNotExist()
            server.appReleaseStatus = 404
            refresh()
            waitFor("App updates are not available on this server yet. Contact your administrator.")
            compose.onNodeWithText("Download update").assertDoesNotExist()
        }
    }
    @Test fun offlineCheckIsDisabled() {
        compose.setContent { PedTheme(darkTheme = true) { AppUpdatesScreen(apiBase = "http://127.0.0.1:1", online = false) } }
        compose.onNodeWithText("Connect to the internet to check for updates.").assertExists()
        compose.onNodeWithText("Check for updates").assertIsNotEnabled()
        compose.onNodeWithText("Download update").assertDoesNotExist()
    }
}
