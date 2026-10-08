package com.ped.mobile

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ped.mobile.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class AppRelease(val id: String, val version: String, val code: Long, val size: Long, val notes: String) {
    companion object {
        fun read(json: org.json.JSONObject): AppRelease {
            val release = AppRelease(json.getString("id"), json.getString("version_name"), json.getLong("version_code"), json.getLong("size_bytes"), json.optString("notes"))
            require(Regex("^[a-f0-9]{32}$").matches(release.id) && release.code > 0 && release.size > 0 && release.version.isNotBlank()) { "The server returned invalid release details. Try again later." }
            return release
        }
    }
}

@Composable internal fun AppUpdatesScreen(modifier: Modifier = Modifier, apiBase: String, online: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember(apiBase) { mutableStateOf(false) }
    var checked by remember(apiBase) { mutableStateOf(false) }
    var release by remember(apiBase) { mutableStateOf<AppRelease?>(null) }
    var error by remember(apiBase) { mutableStateOf("") }
    var downloadHint by remember { mutableStateOf(false) }
    fun check() {
        if (loading) return
        loading = true; error = ""; checked = false; release = null
        scope.launch {
            try {
                release = withContext(Dispatchers.IO) { ApiClient(apiBase).latestAppRelease()?.let(AppRelease::read) }
                checked = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "Could not check for updates. Try again." }
            finally { loading = false }
        }
    }
    LaunchedEffect(apiBase, online) { if (online) check() }
    val latest = release
    val newer = latest != null && latest.code > BuildConfig.VERSION_CODE
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Keep CIMS up to date", style = MaterialTheme.typography.headlineSmall)
            Text("Installed: ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}", style = MaterialTheme.typography.bodyLarge)
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        !online -> Text("Connect to the internet to check for updates.")
                        loading -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Checking for updates…") }
                        error.isNotBlank() -> Text(error, color = MaterialTheme.colorScheme.error)
                        checked && latest == null -> Text("No app release has been published yet.")
                        checked -> {
                            Text(if (newer) "Update available" else if (latest!!.code == BuildConfig.VERSION_CODE.toLong()) "You’re up to date" else "Your installed build is newer", style = MaterialTheme.typography.titleLarge)
                            Text("Published: ${latest!!.version} · Build ${latest.code}")
                            Text(String.format(Locale.getDefault(), "%.1f MB", latest.size / (1024.0 * 1024.0)), style = MaterialTheme.typography.bodyMedium)
                            if (latest.notes.isNotBlank()) { Text("What’s new", style = MaterialTheme.typography.titleMedium); Text(latest.notes) }
                        }
                    }
                    if (newer && checked && error.isBlank() && online) {
                        Button(onClick = {
                            try {
                                // Use the configured API origin and validated ID, never an arbitrary URL from metadata.
                                val uri = Uri.parse(apiBase.trimEnd('/') + "/app-release/download/" + latest!!.id)
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                downloadHint = true
                            } catch (_: Exception) { error = "No download app could be opened. Ask your administrator for help." }
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Download update") }
                    }
                    OutlinedButton(onClick = { check() }, enabled = online && !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Check for updates") }
                }
            }
            if (downloadHint) Text("Your browser handles the download. Open the downloaded APK and choose Update.", style = MaterialTheme.typography.bodyMedium)
            Text("Install updates over the existing app. Do not uninstall CIMS or clear its storage: saved offline captures must stay on your device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
