package com.ped.mobile

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.ped.mobile.inspection.LocalTally
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ped.mobile.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PedNotice(message: String, error: Boolean = false) {
    Surface(shape = MaterialTheme.shapes.medium, color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer) {
        Text(message, Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable private fun NetworkNote(online: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.secondary)
        Text(if (online) "Network available · save locally first" else "Offline · your captures stay on this device", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SectionHeading(number: String, title: String, subtitle: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Surface(shape = MaterialTheme.shapes.small, color = MaterialTheme.colorScheme.secondaryContainer) {
            Text(number, Modifier.padding(10.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun LoginScreen(modifier: Modifier, email: String, password: String, busy: Boolean, error: String, rememberMe: Boolean,
    onEmailChange: (String) -> Unit, onPasswordChange: (String) -> Unit, onRememberMeChange: (Boolean) -> Unit, onLogin: () -> Unit) {
    var visible by rememberSaveable { mutableStateOf(false) }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val passwordFocus = remember { FocusRequester() }
    val focus = LocalFocusManager.current
    val emailError = attempted && email.isBlank()
    val passwordError = attempted && password.isBlank()
    fun submit() { attempted = true; if (email.isNotBlank() && password.isNotBlank() && !busy) { focus.clearFocus(); onLogin() } }
    Box(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().fillMaxHeight()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
        ) {
            com.ped.mobile.ui.PedLogo(Modifier.size(88.dp).align(Alignment.CenterHorizontally))
            Text("Welcome back", modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.headlineSmall)
            OutlinedTextField(email, onEmailChange, label = { Text("Email address") }, modifier = Modifier.fillMaxWidth(), enabled = !busy, singleLine = true, isError = emailError,
                supportingText = if (emailError) { { Text("Enter your email address.") } } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }))
            OutlinedTextField(password, onPasswordChange, label = { Text("Password") }, modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus), enabled = !busy, singleLine = true, isError = passwordError,
                supportingText = if (passwordError) { { Text("Enter your password.") } } else null,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { visible = !visible }, enabled = !busy, modifier = Modifier.semantics { contentDescription = if (visible) "Hide password" else "Show password" }) { Text(if (visible) "Hide" else "Show") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberMe, onRememberMeChange, enabled = !busy, modifier = Modifier.semantics { contentDescription = "Remember me" })
                Column { Text("Remember me", style = MaterialTheme.typography.bodyLarge); Text("Reopen your workspace offline.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (error.isNotBlank()) PedNotice(error, true)
            Button(onClick = { submit() }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimaryContainer); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "Signing in…" else "Sign in")
            }
            Text("Internet is required to sign in.",
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CaptureScreen(voyagePicker: @Composable () -> Unit = {}, allowedKinds: Set<CaptureKind> = CaptureKind.entries.toSet(), canInspect: Boolean = true, modifier: Modifier, schema: CaptureSchema, selectedKind: CaptureKind, online: Boolean, queueCount: Int,
    textValues: Map<String, String>, imageValues: Map<String, String>, message: String, messageError: Boolean, saving: Boolean, draftStatus: String, onRemoveImage: (String) -> Unit,
    scrollRequest: ScrollRequest?, onKindSelected: (CaptureKind) -> Unit, onTextChanged: (String, String) -> Unit, onCaptureImage: (ImageFieldSpec) -> Unit, onSaveOffline: () -> Unit, onReset: () -> Unit, onGoToSync: () -> Unit, onPreInspection: () -> Unit) {
    var scanningChassis by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val offsets = remember { mutableStateMapOf<String, Int>() }
    var showValidation by rememberSaveable(selectedKind) { mutableStateOf(false) }
    var pendingKind by remember { mutableStateOf<CaptureKind?>(null) }
    var reset by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }
    var removePhoto by remember { mutableStateOf<ImageFieldSpec?>(null) }
    val hasDraft = textValues.values.any { it.isNotBlank() } || imageValues.values.any { it.isNotBlank() }
    val photos = schema.imageFields.count { !imageValues[it.key].isNullOrBlank() }
    LaunchedEffect(scrollRequest?.requestId) { scrollRequest?.let { offsets[it.fieldKey]?.let { y -> scroll.animateScrollTo((y - 16).coerceAtLeast(0)) } } }
    LaunchedEffect(message) {
        if (message.startsWith("Capture saved on this device")) { showValidation = false; scroll.animateScrollTo(0) }
    }
    fun switchKind(kind: CaptureKind) { onKindSelected(kind); showValidation = false }
    Box(modifier.fillMaxSize().imePadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NetworkNote(online)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Capture a record", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(draftStatus, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CaptureKind.entries.forEach { kind ->
                    FilterChip(selected = kind == selectedKind, onClick = { if (kind != selectedKind) { if (hasDraft) pendingKind = kind else switchKind(kind) } }, enabled = !saving && !scanningChassis && kind in allowedKinds,
                        label = { Text(when (kind) { CaptureKind.PED -> "On Premise"; CaptureKind.OFFDOCK -> "Offdock"; CaptureKind.CONTAINER -> "Container"; CaptureKind.SCRAP -> "Scrap" }) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            if (message.isNotBlank()) PedNotice(message, messageError)
            if (message.startsWith("Capture saved on this device") && !hasDraft) {
                OutlinedButton(onClick = onGoToSync, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("View uploads") }
            }
            if (selectedKind !in allowedKinds) {
                PedNotice("Your role cannot capture this record type. Choose an available type or contact an administrator. Your saved records and draft are kept.")
            }
            if (selectedKind == CaptureKind.PED) voyagePicker()
            SectionHeading("01", schema.label, "Required fields are marked with *. Save on device when ready.")
            schema.textFields.forEach { field ->
                val invalid = showValidation && field.required && textValues[field.key].isNullOrBlank()
                Column(Modifier.onGloballyPositioned { offsets[field.key] = it.positionInParent().y.toInt() }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(textValues[field.key].orEmpty(), { onTextChanged(field.key, it) }, textStyle = if (field.key == "chassis") com.ped.mobile.ui.PedChassisTextStyle else MaterialTheme.typography.bodyLarge, label = { Text(field.label + if (field.required) " *" else "") },
                        modifier = Modifier.fillMaxWidth(), enabled = !saving, minLines = if (field.multiline) 3 else 1, maxLines = if (field.multiline) 6 else 1, singleLine = !field.multiline,
                        isError = invalid, supportingText = if (invalid) { { Text("${field.label} is required.") } } else null,
                        keyboardOptions = KeyboardOptions(capitalization = if (field.key in listOf("chassis", "container_number")) KeyboardCapitalization.Characters else KeyboardCapitalization.Sentences))
                    if (field.key == "chassis") {
                        com.ped.mobile.inspection.ChassisPhotoScanner(
                            enabled = !saving && selectedKind in allowedKinds,
                            onUseNumber = { onTextChanged(field.key, it) },
                            onBusyChange = { scanningChassis = it }
                        )
                    }
                }
            }
            SectionHeading("02", "Photo evidence", "$photos of ${schema.imageFields.size} photos added · optional views can be left empty.")
            schema.imageFields.forEach { field ->
                val source = imageValues[field.key].orEmpty()
                val bitmap by produceState<android.graphics.Bitmap?>(null, source) { value = withContext(Dispatchers.Default) { source.decodeBitmap() } }
                val invalid = showValidation && field.required && source.isBlank()
                OutlinedCard(Modifier.fillMaxWidth().onGloballyPositioned { offsets[field.key] = it.positionInParent().y.toInt() }) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (source.isBlank()) Icons.Outlined.AddCircle else Icons.Outlined.CheckCircle, contentDescription = null, tint = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary)
                            Column(Modifier.weight(1f)) {
                                Text(field.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(if (source.isNotBlank()) "Photo added" else if (field.required) "Required photo" else "Optional photo", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        if (bitmap == null && source.isNotBlank()) Text("Photo unavailable. Retake the photo if needed.")
                    bitmap?.let { Image(it.asImageBitmap(), contentDescription = "${field.label} preview", modifier = Modifier.fillMaxWidth().height(96.dp), contentScale = ContentScale.Fit) }
                        if (source.isNotBlank()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { preview = field.label to source }, modifier = Modifier.heightIn(min = 48.dp)) { Text("View full photo") }
                            if (!field.required) TextButton(onClick = { removePhoto = field }, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove photo") }
                        }
                        if (invalid) Text("Add ${field.label.lowercase()} before saving.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = { onCaptureImage(field) }, enabled = !saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (source.isBlank()) "Take ${field.label.lowercase()} photo" else "Retake ${field.label.lowercase()} photo") }
                    }
                }
            }
            TextButton(onClick = { reset = true }, enabled = hasDraft && !saving && !scanningChassis, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear this form") }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("$photos photos · ${if (hasDraft) "Draft" else "New capture"}", style = MaterialTheme.typography.labelMedium)
                Button(onClick = { showValidation = true; onSaveOffline() }, enabled = !saving && !scanningChassis && selectedKind in allowedKinds, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Text(if (saving) "Please wait…" else "Save on device") }
            }
        }
        }
    }
    }
    preview?.let { (label, source) -> PhotoReview(label, source) { preview = null } }
    removePhoto?.let { field -> AlertDialog(onDismissRequest = { removePhoto = null }, title = { Text("Remove ${field.label.lowercase()}?") }, text = { Text("You can take another photo before saving this capture.") }, confirmButton = { TextButton(onClick = { onRemoveImage(field.key); removePhoto = null }) { Text("Remove photo") } }, dismissButton = { TextButton(onClick = { removePhoto = null }) { Text("Keep photo") } }) }
    pendingKind?.let { kind -> AlertDialog(onDismissRequest = { pendingKind = null }, title = { Text("Change record type?") }, text = { Text("Your unsaved fields and photos will be cleared. Saved captures are kept.") }, confirmButton = { TextButton(onClick = { pendingKind = null; switchKind(kind) }) { Text("Change type") } }, dismissButton = { TextButton(onClick = { pendingKind = null }) { Text("Keep editing") } }) }
    if (reset) AlertDialog(onDismissRequest = { reset = false }, title = { Text("Clear this form?") }, text = { Text("This removes only the unsaved fields and photos from your current capture.") }, confirmButton = { TextButton(onClick = { reset = false; showValidation = false; onReset() }) { Text("Clear form") } }, dismissButton = { TextButton(onClick = { reset = false }) { Text("Keep editing") } })
}


@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun SyncScreen(canCapture: Boolean = true, canInspect: Boolean = true, modifier: Modifier, queue: List<PendingCapture>, busy: Boolean, message: String, messageError: Boolean, online: Boolean,
    history: List<UploadReceipt>, tally: List<LocalTally>, legacyCount: Int, onOpenTally: () -> Unit,
    onRetry: (String) -> Unit, onSignIn: () -> Unit, onReview: (String) -> Unit,
    onGoToCapture: () -> Unit, onRefresh: () -> Unit, onSyncAll: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var uploadFilter by rememberSaveable { mutableStateOf("Pending") }
    val showHistory = uploadFilter == "Uploaded"
    val failedCount = queue.count { it.error.isNotBlank() } + tally.count { it.state != "synced" && it.error.isNotBlank() }
    val tallyPending = tally.count { it.state != "synced" }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Text("Uploads", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(when { !canCapture -> "$tallyPending tally vehicles waiting"; !canInspect -> "${queue.size} captures waiting"; else -> "${queue.size + tallyPending} waiting · ${queue.size} captures · $tallyPending tally vehicles" }, style = MaterialTheme.typography.bodyLarge)
                NetworkNote(online)
            }
            item { com.ped.mobile.sync.AutoSyncSettings() }
            item { FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Pending", "Failed", "Uploaded").forEach { label ->
                    val count = when (label) {
                        "Pending" -> queue.size + tallyPending - failedCount
                        "Failed" -> failedCount
                        else -> history.size + tally.count { it.state == "synced" }
                    }
                    FilterChip(selected = uploadFilter == label, onClick = { uploadFilter = label },
                        label = { Text("$label ($count)") }, modifier = Modifier.heightIn(min = 48.dp))
                }
                TextButton(onClick = onRefresh, enabled = !busy) { Text("Refresh") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("Search chassis or record") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(message.ifBlank { "Uploading…" }, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
            else if (message.isNotBlank()) item { PedNotice(message, messageError) }
            if (legacyCount > 0) item { PedNotice("$legacyCount older captures have no verified account owner. They are preserved on this device and excluded from uploads. Ask an administrator to recover and verify ownership before sending them.", true) }
            if (!showHistory) {
                val matchingQueue = queue.filter { it.title.contains(query, true) && (it.error.isNotBlank() == (uploadFilter == "Failed")) }
                val matchingPendingTally = tally.filter { it.state != "synced" && it.payload.contains(query, true) && (it.error.isNotBlank() == (uploadFilter == "Failed")) }
                item {
                    Button(onClick = onSyncAll, enabled = !busy && online && queue.size + tallyPending > 0,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Upload all (${queue.size + tallyPending})") }
                }
                if (matchingQueue.isEmpty() && matchingPendingTally.isEmpty()) item {
                    Text(if (query.isNotBlank()) "No records match your search."
                        else if (queue.isEmpty() && tallyPending == 0) "Everything saved for this account has been uploaded. Open Uploaded to review confirmations."
                        else if (uploadFilter == "Failed") "No failed uploads."
                        else "No pending records. Check Failed for records that need attention.")
                }
                items(matchingQueue, key = { "capture-${it.id}" }) { item ->
                    OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(if (item.error.isBlank()) "Pending · saved on device" else "Failed · needs attention")
                        Text(displayTime(item.createdAt), style = MaterialTheme.typography.bodySmall)
                        if (item.error.isNotBlank()) PedNotice(item.error, true)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onReview(item.id) }, enabled = !busy) { Text("Review capture") }
                            OutlinedButton(onClick = { onRetry(item.id) }, enabled = !busy && online) { Text(if (item.error.isBlank()) "Upload" else "Retry upload") }
                        }
                    } }
                }
                items(matchingPendingTally, key = { "tally-${it.id}" }) { item ->
                    OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(item.data.optString("chassis_number"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text("Tally · ${item.voyage.vessel} / ${item.voyage.voyage}")
                        Text(if (item.error.isBlank()) "Pending · saved on device" else "Failed · needs attention")
                        if (item.error.isNotBlank()) { PedNotice(item.error, true); Text(item.recoveryAction) }
                        TextButton(onClick = onOpenTally, enabled = !busy) { Text("Review in tally") }
                    } }
                }
                item { TextButton(onClick = onSignIn, enabled = !busy) { Text("Session expired? Sign in again") } }
            } else {
                val matching = history.filter { it.title.contains(query, true) }
                val matchingTally = tally.filter { it.state == "synced" && (it.payload.contains(query, true) || it.reportNumber.contains(query, true)) }
                if (matching.isEmpty() && matchingTally.isEmpty()) item { Text("No uploaded records match. History includes confirmations saved on this device.") }
                items(matching, key = { "receipt-${it.id}" }) { item -> OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(item.title, fontWeight = FontWeight.Bold)
                    Text("Uploaded · confirmed ${displayTime(item.confirmedAt)}")
                    if (item.reference.isNotBlank()) Text("Server reference: ${item.reference}")
                    Text("Local reference: ${item.id}", style = MaterialTheme.typography.bodySmall)
                } } }
                items(matchingTally, key = { "receipt-tally-${it.id}" }) { item -> OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(item.data.optString("chassis_number"), fontWeight = FontWeight.Bold)
                    Text("Uploaded tally · ${item.reportNumber}")
                    Text(if (item.syncedAt.isBlank()) "Confirmation time unavailable for this older record" else "Confirmed ${displayTime(item.syncedAt)}", style = MaterialTheme.typography.bodySmall)
                    Text("Report completion is managed separately.", style = MaterialTheme.typography.bodySmall)
                } } }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PhotoReview(label: String, source: String, onClose: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, source) { value = withContext(Dispatchers.IO) { source.decodeBitmap() } }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onClose) { Text("Close") }
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds().pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 5f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan } }, contentAlignment = Alignment.Center) {
                    if (bitmap == null) Text("Photo unavailable. Close and retake the photo if needed.")
                    bitmap?.let { Image(it.asImageBitmap(), label, modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y), contentScale = ContentScale.Fit) }
                }
                FlowRow(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.Center) {
                    TextButton(onClick = { scale = (scale - .5f).coerceAtLeast(1f) }) { Text("Zoom out") }
                    TextButton(onClick = { scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero }) { Text("Reset") }
                    TextButton(onClick = { scale = (scale + .5f).coerceAtMost(5f) }) { Text("Zoom in") }
                }
            }
        }
    }
}

@Composable internal fun CaptureReview(item: PendingCapture, onClose: () -> Unit) {
    val schema = CaptureSchemas.all.getValue(item.kind)
    var photo by remember { mutableStateOf<Pair<String, String>?>(null) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                TextButton(onClick = onClose) { Text("Back to uploads") }
                Text(item.title, style = MaterialTheme.typography.titleLarge)
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Saved on device · ${displayTime(item.createdAt)}")
                    if (item.kind == CaptureKind.PED) Text(if (item.payload["inspection_voyage_id"].isNullOrBlank()) "Vessel / voyage not assigned" else "${item.payload["vessel_name"]} · Voyage ${item.payload["voyage_number"]}")
                    schema.textFields.forEach { field -> Column { Text(field.label, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(item.payload[field.key].orEmpty().ifBlank { "Not recorded" }) } }
                    schema.imageFields.forEach { field -> item.payload[field.key]?.takeIf { it.isNotBlank() }?.let { source ->
                        OutlinedButton(onClick = { photo = field.label to source }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("View ${field.label}") }
                    } }
                    if (item.error.isNotBlank()) PedNotice(item.error, true)
                }
            }
        }
    }
    photo?.let { (label, source) -> PhotoReview(label, source) { photo = null } }
}

internal fun displayTime(value: String): String = runCatching {
    java.time.Instant.parse(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm"))
}.getOrDefault(value)
