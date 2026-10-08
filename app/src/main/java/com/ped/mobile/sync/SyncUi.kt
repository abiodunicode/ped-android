package com.ped.mobile.sync

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

@Composable fun SyncBanner() {
    val context = LocalContext.current
    val state by SyncCoordinator.status.collectAsState()
    var dismissed by remember { mutableStateOf(-1L) }
    LaunchedEffect(state.revision) {
        if (!state.running) { delay(12000); dismissed = state.revision }
    }
    if (state.owner != AutoSync.session(context)?.owner || state.message.isBlank() || dismissed == state.revision) return
    Surface(color = if (state.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(state.message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (state.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

@Composable fun AutoSyncSettings() {
    val context = LocalContext.current
    val settings = remember { SyncSettings(context) }
    var enabled by remember { mutableStateOf(settings.enabled) }
    var wifiOnly by remember { mutableStateOf(settings.wifiOnly) }
    var notifications by remember { mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Automatic sync", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Upload saved records when connected", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(modifier = Modifier.semantics { contentDescription = "Automatic sync" }, checked = enabled, onCheckedChange = {
                    enabled = it; settings.enabled = it; AutoSync.settingsChanged(context)
                })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Wi-Fi only", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(modifier = Modifier.semantics { contentDescription = "Wi-Fi only automatic sync" }, checked = wifiOnly, enabled = enabled, onCheckedChange = {
                    wifiOnly = it; settings.wifiOnly = it; AutoSync.settingsChanged(context)
                })
            }
            Text(if (wifiOnly) "Automatic uploads wait for unmetered Wi-Fi. Manual sync can use mobile data." else "Automatic uploads can use Wi-Fi or mobile data. Photo uploads use data.", style = MaterialTheme.typography.bodySmall)
            Text("Manual sync and retry are always available. Keep Remember me enabled for uploads after the app closes.", style = MaterialTheme.typography.bodySmall)
            if (!notifications && Build.VERSION.SDK_INT >= 33) {
                OutlinedButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Enable upload notifications") }
            }
        }
    }
}
