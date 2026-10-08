package com.ped.mobile

import com.ped.mobile.inspection.canCaptureEvidence
import com.ped.mobile.inspection.canViewTally

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.ui.draw.alpha
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.ped.mobile.data.AppScreen
import com.ped.mobile.data.CaptureKind
import com.ped.mobile.inspection.InspectionAccount
import com.ped.mobile.ui.PedBrand
import kotlinx.coroutines.launch

internal fun AppScreen.pageTitle(): String = when (this) {
    AppScreen.CAPTURE -> "Capture Evidence"
    AppScreen.INSPECTION -> "Inspection Tally"
    AppScreen.SYNC -> "Uploads"
    AppScreen.APP_UPDATES -> "App updates"
    AppScreen.ACCOUNT -> "Account & Settings"
    AppScreen.LOGIN -> "Sign in"
}

/** Shared navigation for evidence, tally and uploads. Each destination keeps its own work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PedWorkspace(
    screen: AppScreen,
    account: InspectionAccount?,
    online: Boolean,
    pendingCount: Int,
    busy: Boolean,
    onNavigate: (AppScreen) -> Unit,
    snackbarHost: @Composable () -> Unit = {},
    subtitle: String = "",
    onBack: (() -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit
) {
    if (screen == AppScreen.LOGIN) {
        Scaffold(snackbarHost = snackbarHost, content = content)
        return
    }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Keep sufficient content width; large text always gets a dismissible drawer.
        val permanent = maxWidth >= 1000.dp && density.fontScale <= 1.3f
        val sheetWidth = minOf(maxWidth * .9f, 320.dp)
        val navigate: (AppScreen) -> Unit = { destination ->
            if (!busy) scope.launch {
                focus.clearFocus()
                keyboard?.hide()
                drawer.close()
                if (destination != screen) onNavigate(destination)
            }
        }
        val body: @Composable () -> Unit = {
            Scaffold(
                topBar = {
                    Surface(shadowElevation = 1.dp) {
                        Column {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (!permanent) IconButton(onClick = { focus.clearFocus(); keyboard?.hide(); scope.launch { drawer.open() } }, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                                Icon(Icons.Outlined.Menu, "Open navigation")
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(screen.pageTitle(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                Text(subtitle.ifBlank { if (online) "Network available" else "Offline · saved on device" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (onBack != null) IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to tally") }
                        }
                        if (account != null) com.ped.mobile.sync.SyncBanner()
                        }
                    }
                },
                snackbarHost = snackbarHost,
                content = content
            )
        }
        if (permanent) {
            Row(Modifier.fillMaxSize()) {
                WorkspaceSidebar(screen, account, pendingCount, busy, navigate, null, Modifier.width(280.dp))
                Box(Modifier.weight(1f)) { body() }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawer,
                // A left-edge swipe must not interfere with photo zoom or text editing.
                gesturesEnabled = drawer.isOpen,
                drawerContent = {
                    ModalDrawerSheet(modifier = Modifier.width(sheetWidth), drawerContainerColor = PedBrand.Navy) {
                        WorkspaceSidebar(screen, account, pendingCount, busy, navigate, { scope.launch { drawer.close() } })
                    }
                }
            ) { body() }
        }
    }
    BackHandler(enabled = drawer.isOpen) { scope.launch { drawer.close() } }
}

@Composable private fun WorkspaceSidebar(
    screen: AppScreen, account: InspectionAccount?, pendingCount: Int, busy: Boolean,
    onNavigate: (AppScreen) -> Unit, onClose: (() -> Unit)?, modifier: Modifier = Modifier
) {
    Surface(modifier.fillMaxHeight(), color = PedBrand.Navy, contentColor = PedBrand.OnNavy) {
        BoxWithConstraints(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.ped.mobile.ui.PedLogo(Modifier.size(56.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text("CIMS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Field operations", style = MaterialTheme.typography.bodySmall)
                }
                if (onClose != null) IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close navigation") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Spacer(Modifier.height(16.dp))
                Text(account?.name?.ifBlank { account.email } ?: "CIMS account", style = MaterialTheme.typography.titleSmall)
                if (!account?.name.isNullOrBlank()) Text(account?.email.orEmpty(), style = MaterialTheme.typography.bodySmall)
                Text(when { account?.accountType == "admin" -> "Administrator"; account.canCaptureEvidence() && !account.canViewTally() -> "Picture evidence only"; account.canViewTally() && !account.canCaptureEvidence() -> "Inspection tally only"; else -> "Access assigned by your administrator" }, style = MaterialTheme.typography.bodySmall, color = PedBrand.OnNavy.copy(alpha = .8f))
                Spacer(Modifier.height(16.dp))
                Text("WORKSPACE", style = MaterialTheme.typography.labelMedium, color = PedBrand.OnNavy.copy(alpha = .8f))
                val destinations = buildList {
                    if (account != null && CaptureKind.entries.any { account.can(it.name.lowercase() + ".capture") }) add(AppScreen.CAPTURE)
                    if (account?.can("inspections.view") == true) add(AppScreen.INSPECTION)
                    // Even a revoked user must be able to see preserved local uploads.
                    add(AppScreen.SYNC)
                }
                destinations.forEach { destination ->
                    SidebarItem(destination, screen, busy, if (destination == AppScreen.SYNC) pendingCount else 0, onNavigate)
                }
                Spacer(Modifier.height(16.dp))
                Text("Save on your device first. Upload when you have internet.", style = MaterialTheme.typography.bodySmall, color = PedBrand.OnNavy.copy(alpha = .8f))
            }
            }
            Column {
            HorizontalDivider(color = PedBrand.OnNavy.copy(alpha = .2f), modifier = Modifier.padding(vertical = 12.dp))
            SidebarItem(AppScreen.APP_UPDATES, screen, busy, 0, onNavigate)
            SidebarItem(AppScreen.ACCOUNT, screen, busy, 0, onNavigate)
            }
        }
        }
    }
}

@Composable private fun SidebarItem(destination: AppScreen, current: AppScreen, busy: Boolean, count: Int, navigate: (AppScreen) -> Unit) {
    val selected = current == destination
    Surface(
        color = if (selected) PedBrand.Gold else PedBrand.Navy,
        contentColor = if (selected) PedBrand.Navy else PedBrand.OnNavy,
        shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).alpha(if (busy) .5f else 1f)
            .selectable(selected = selected, enabled = !busy, role = Role.Tab, onClick = { navigate(destination) })
    ) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(when (destination) {
                AppScreen.CAPTURE -> Icons.Outlined.Edit
                AppScreen.INSPECTION -> Icons.AutoMirrored.Outlined.List
                AppScreen.SYNC -> Icons.Outlined.Refresh
                else -> Icons.Outlined.Settings
            }, null)
            Text(destination.pageTitle(), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            if (count > 0) Text(count.toString(), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable internal fun AccountScreen(
    modifier: Modifier, account: InspectionAccount?, online: Boolean, serverStatus: String,
    rememberMe: Boolean, busy: Boolean, onRememberMe: (Boolean) -> Unit,
    onCheckConnection: () -> Unit, onLogout: () -> Unit
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Your account", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    if (!account?.name.isNullOrBlank()) Text(account?.name.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    Text(account?.email ?: "Connect to verify your account.")
                    Text(if (account?.accountType == "admin") "Administrator" else "Permissions are managed by your administrator.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Offline work", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Keep me signed in", fontWeight = FontWeight.SemiBold); Text("Reopen your saved workspace offline.", style = MaterialTheme.typography.bodyMedium) }
                        Switch(rememberMe, onRememberMe, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Keep me signed in" })
                    }
                    Text("Evidence, tally vehicles and unfinished drafts stay on this device. Saved records can upload automatically when connected. Manage this in Uploads.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedCard {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Connection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (online) "Network available" else "Offline")
                    Text(serverStatus, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = onCheckConnection, enabled = online && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check connection") }
                }
            }
            OutlinedButton(onClick = onLogout, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Sign out") }
            Text("CIMS ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
