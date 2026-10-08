package com.ped.mobile

import com.ped.mobile.sync.AutoSync
import com.ped.mobile.sync.SyncCoordinator
import androidx.compose.runtime.collectAsState
import com.ped.mobile.inspection.canCapture
import com.ped.mobile.inspection.canCaptureEvidence
import com.ped.mobile.inspection.canViewTally
import com.ped.mobile.inspection.homeScreen
import com.ped.mobile.inspection.allowsScreen

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import com.ped.mobile.ui.PedTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.runtime.snapshotFlow
import com.ped.mobile.inspection.InspectionRepository
import com.ped.mobile.inspection.InspectionAccount
import com.ped.mobile.inspection.LocalTally
import com.ped.mobile.data.UploadReceipt
import com.ped.mobile.network.InspectionApiException
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.ped.mobile.data.AppScreen
import com.ped.mobile.data.CaptureKind
import com.ped.mobile.data.CaptureSchema
import com.ped.mobile.data.CaptureSchemas
import com.ped.mobile.data.ImageFieldSpec
import com.ped.mobile.data.PendingCapture
import com.ped.mobile.data.QueueStore
import com.ped.mobile.data.SessionStore
import com.ped.mobile.inspection.InspectionScreen
import com.ped.mobile.network.ApiClient
import com.ped.mobile.network.isOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PedTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PedMobileApp()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PedMobileApp(apiBase: String = BuildConfig.API_BASE_URL) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sessionStore = remember { SessionStore(context) }
    val repository = remember(apiBase) { InspectionRepository.get(context, apiBase) }
    val apiClient = remember(apiBase) { ApiClient(apiBase) }
    val snackbarHostState = remember { SnackbarHostState() }

    var token by rememberSaveable { mutableStateOf(sessionStore.readToken()) }
    var account by remember(token) { mutableStateOf(token?.let { repository.cachedAccount(it) }) }
    val owner = account?.let(repository::owner)
    val queueStore = remember(owner) { QueueStore(context, owner ?: "locked-unverified") }
    var draftReady by remember(owner) { mutableStateOf(false) }
    var draftStatus by remember(owner) { mutableStateOf("Loading draft…") }
    var receipts by remember(owner) { mutableStateOf(emptyList<UploadReceipt>()) }
    var tallyRecords by remember(owner) { mutableStateOf(emptyList<LocalTally>()) }
    var reviewCapture by remember { mutableStateOf<PendingCapture?>(null) }
    var uploadOne by remember { mutableStateOf<String?>(null) }
    var serverStatus by remember { mutableStateOf("Server not checked") }
    val legacyCount = remember { QueueStore(context).loadQueue().size }
    var screen by rememberSaveable {
        mutableStateOf(if (token.isNullOrBlank()) AppScreen.LOGIN else AppScreen.CAPTURE)
    }

    var loginEmail by rememberSaveable { mutableStateOf("") }
    var loginPassword by rememberSaveable { mutableStateOf("") }
    var rememberMe by rememberSaveable { mutableStateOf(sessionStore.readRememberMe()) }
    var loginBusy by remember { mutableStateOf(false) }
    var loginError by rememberSaveable { mutableStateOf("") }

    var selectedKind by rememberSaveable { mutableStateOf(CaptureKind.PED) }
    val draft: CaptureDraftViewModel = viewModel()
    val textValues = draft.textValues
    val imageValues = draft.imageValues
    val queueItems = remember(owner) { mutableStateListOf<PendingCapture>() }

    var captureError by remember { mutableStateOf(false) }
    var syncError by remember { mutableStateOf(false) }
    var captureMessage by draft.captureMessage
    var captureBusy by draft.captureBusy
    val uploadStatus by SyncCoordinator.status.collectAsState()
    val syncBusy = uploadStatus.owner == owner && uploadStatus.running
    var syncMessage by draft.syncMessage
    var logoutBusy by remember { mutableStateOf(false) }
    var scrollRequest by remember { mutableStateOf<ScrollRequest?>(null) }
    var activeImageField by draft.activeImageField
    var pendingImagePath by draft.pendingImagePath

    val schema = remember(selectedKind) { CaptureSchemas.all.getValue(selectedKind) }
    DisposableEffect(Unit) {
        onDispose {
        }
    }

    var online by remember { mutableStateOf(isOnline(context)) }
    var confirmLogout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) { online = isOnline(context); delay(2000) }
    }

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(owner) {
        if (owner != null && android.os.Build.VERSION.SDK_INT >= 33) {
            val prefs = context.getSharedPreferences("cims-auto-sync", Context.MODE_PRIVATE)
            if (!prefs.getBoolean("notification_prompted", false) && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                prefs.edit().putBoolean("notification_prompted", true).apply()
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val field = activeImageField ?: return@rememberLauncherForActivityResult
        val imagePath = pendingImagePath

        if (!success || imagePath.isNullOrBlank()) {
            captureError = false
            captureMessage = "Image capture was cancelled."
            activeImageField = null
            pendingImagePath = null
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val dataUrl = withContext(Dispatchers.Default) {
                context.optimizedDataUrlFromFile(imagePath)
            }

            if (dataUrl == null) {
                captureError = true
                captureMessage = "Image was captured, but optimization failed."
            } else {
                imageValues[field.key] = dataUrl
                captureError = false
                captureMessage = "${field.label} captured."
            }

            activeImageField = null
            pendingImagePath = null
        }
    }
    LaunchedEffect(selectedKind) {
        schema.textFields.forEach { textValues.putIfAbsent(it.key, "") }
        schema.imageFields.forEach { imageValues.putIfAbsent(it.key, "") }
        captureMessage = ""
    }

    fun refreshQueue() {
        queueItems.clear()
        queueItems.addAll(queueStore.loadQueue())
    }

    LaunchedEffect(owner, screen, draft.queueRevision.value) {
        val updated = withContext(Dispatchers.IO) { queueStore.loadQueue() }
        queueItems.clear(); queueItems.addAll(updated)
        receipts = withContext(Dispatchers.IO) { queueStore.history() }
        tallyRecords = owner?.let { withContext(Dispatchers.IO) { repository.store.list(it) } } ?: emptyList()
    }

    LaunchedEffect(token) {
        if (token != null && account == null) {
            try { account = withContext(Dispatchers.IO) { repository.verifyAccount(token!!) }; serverStatus = "Server checked ${java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}" }
            catch (e: Exception) { loginError = "Connect and sign in to confirm your account. Saved records are kept."; screen = AppScreen.LOGIN }
        }
    }
    LaunchedEffect(token, online, screen) {
        if (token != null && online) {
            try { account = withContext(Dispatchers.IO) { repository.verifyAccount(token!!) } }
            catch (_: Exception) { /* Keep offline records and the last verified permissions. */ }
        }
    }
    LaunchedEffect(owner) {
        draftReady = false
        textValues.clear(); imageValues.clear()
        if (owner != null) {
            val saved = withContext(Dispatchers.IO) { queueStore.readDraft() }
            if (saved != null) {
                selectedKind = saved.first
                val imageKeys = CaptureSchemas.all.getValue(saved.first).imageFields.map { it.key }.toSet()
                saved.second.forEach { (key, value) -> if (key in imageKeys) imageValues[key] = value else textValues[key] = value }
            }
            draftStatus = if (saved != null) "Draft restored on this device" else "Draft saved automatically"
            draftReady = true
        }
    }
    LaunchedEffect(owner, draftReady) {
        if (owner == null || !draftReady) return@LaunchedEffect
        snapshotFlow { selectedKind to (textValues.toMap() + imageValues.toMap()) }
            .distinctUntilChanged().collect { (kind, payload) ->
                try {
                    draftStatus = "Saving draft…"
                    withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { queueStore.saveDraft(kind, payload) }
                    draftStatus = "Draft saved on this device"
                } catch (e: Exception) { draftStatus = "Draft could not be saved. Keep this screen open and retry saving." }
            }
    }

    fun clearCurrentForm() {
        schema.textFields.forEach { textValues[it.key] = "" }
        schema.imageFields.forEach { imageValues[it.key] = "" }
    }

    fun finishLogout() {
        sessionStore.clearToken()
        draftReady = false
        loginEmail = ""
        loginPassword = ""
        token = null
        screen = AppScreen.LOGIN
    }

    fun startLogout() {
        if (logoutBusy) {
            return
        }

        val activeToken = token
        AutoSync.deactivate(context)
        logoutBusy = true
        scope.launch {
            val result = if (activeToken.isNullOrBlank() || !isOnline(context)) {
                Result.success(Unit)
            } else {
                withContext(Dispatchers.IO) {
                    apiClient.logout(activeToken)
                }
            }

            logoutBusy = false
            result.exceptionOrNull()?.message?.let { snackbarHostState.showSnackbar(it) }
            finishLogout()
        }
    }

    fun syncCaptures(onlyId: String? = null) {
        if (syncBusy) return
        val activeToken = token ?: return
        val activeOwner = owner ?: return
        if (!isOnline(context)) { syncMessage = "This device is offline. Connect and retry."; return }
        draft.viewModelScope.launch {
            SyncCoordinator.run(context.applicationContext, apiBase, activeToken, activeOwner, false, onlyId) {
                AutoSync.session(context)?.let { it.token == activeToken && it.owner == activeOwner } == true
            }
            draft.queueRevision.value++
        }
    }
    LaunchedEffect(token, owner) {
        val actor = owner
        val key = token
        if (actor != null && key != null) AutoSync.activate(context, apiBase, actor, key)
        else if (key == null) AutoSync.deactivate(context)
    }
    LaunchedEffect(owner, online) {
        if (owner == null) return@LaunchedEffect
        while (true) {
            AutoSync.schedule(context)
            delay(30000)
        }
    }
    LaunchedEffect(uploadStatus.revision) {
        if (uploadStatus.owner == owner) {
            syncMessage = uploadStatus.message
            syncError = uploadStatus.error
            draft.queueRevision.value++
        }
    }
    LaunchedEffect(uploadOne) {
        uploadOne?.let { syncCaptures(it); uploadOne = null }
    }

    // Guard restored screens and role changes as well as sidebar navigation.
    val allowedScreen = if (account.allowsScreen(screen)) screen else account.homeScreen()
    LaunchedEffect(allowedScreen) { if (screen != allowedScreen) screen = allowedScreen }
    if (screen != allowedScreen) return

    val workspaceState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    reviewCapture?.let { item -> CaptureReview(item) { reviewCapture = null } }
    if (screen == AppScreen.INSPECTION && !token.isNullOrBlank()) {
        workspaceState.SaveableStateProvider("inspection-${owner}") {
        InspectionScreen(token = token!!, onExit = { screen = account.homeScreen() }, apiBase = apiBase,
            onNavigate = { screen = it }, capturePending = queueItems.count { account.canCapture(it.kind) })
        }
        return
    }

    BackHandler(enabled = screen == AppScreen.SYNC || screen == AppScreen.ACCOUNT || screen == AppScreen.APP_UPDATES) { if (!syncBusy) screen = account.homeScreen() }
    if (confirmLogout) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("Sign out of CIMS?") },
        text = { Text("Saved captures stay on this device. Saved drafts belong to your account and can be resumed when you sign in again. You’ll need internet to sign in again.") },
        confirmButton = { TextButton(onClick = { confirmLogout = false; startLogout() }) { Text("Sign out") } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Stay signed in") } }
    )
    PedWorkspace(
        screen = screen, account = account, online = online,
        pendingCount = queueItems.count { account.canCapture(it.kind) } + if (account.canViewTally()) tallyRecords.count { it.state != "synced" } else 0,
        busy = logoutBusy || captureBusy,
        onNavigate = { screen = it },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        when (screen) {
            AppScreen.APP_UPDATES -> AppUpdatesScreen(Modifier.padding(padding), apiBase, online)
            AppScreen.ACCOUNT -> AccountScreen(
                modifier = Modifier.padding(padding), account = account, online = online,
                serverStatus = serverStatus, rememberMe = rememberMe, busy = logoutBusy || syncBusy,
                onRememberMe = { rememberMe = it; sessionStore.writeRememberMe(it); if (it) token?.let(sessionStore::writeToken) else sessionStore.clearToken() },
                onCheckConnection = {
                    scope.launch {
                        try {
                            account = withContext(Dispatchers.IO) { repository.verifyAccount(requireNotNull(token)) }
                            serverStatus = "Server connected"
                        } catch (e: Exception) { serverStatus = "Server unavailable. Saved work is kept." }
                    }
                },
                onLogout = { confirmLogout = true }
            )
            AppScreen.INSPECTION -> Unit
            AppScreen.LOGIN -> LoginScreen(
                modifier = Modifier.padding(padding),
                email = loginEmail,
                password = loginPassword,
                busy = loginBusy,
                error = loginError,
                rememberMe = rememberMe,
                onEmailChange = { loginEmail = it },
                onPasswordChange = { loginPassword = it },
                onRememberMeChange = {
                    rememberMe = it
                    sessionStore.writeRememberMe(it)
                },
                onLogin = {
                    loginBusy = true
                    loginError = ""
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            apiClient.login(loginEmail.trim(), loginPassword).mapCatching { newToken ->
                                repository.verifyAccount(newToken)
                                newToken
                            }
                        }

                        loginBusy = false
                        result.onSuccess { newToken ->
                            if (rememberMe) {
                                sessionStore.writeToken(newToken)
                            } else {
                                sessionStore.clearToken()
                            }
                            loginPassword = ""
                            token = newToken
                            account = repository.cachedAccount(newToken)
                            serverStatus = "Server checked ${java.time.LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))}"
                            screen = account.homeScreen()
                        }.onFailure { error ->
                            loginError = error.message ?: "Unable to sign in."
                        }
                    }
                }
            )

            AppScreen.CAPTURE -> CaptureScreen(
                voyagePicker = { if (owner != null && token != null && draftReady) PedVoyagePicker(owner, token!!, apiBase, online, !captureBusy, textValues) { key, value -> textValues[key] = value } },
                allowedKinds = CaptureKind.entries.filter { account?.can(it.name.lowercase() + ".capture") == true }.toSet(),
                canInspect = account.canViewTally(),
                modifier = Modifier.padding(padding),
                schema = schema,
                selectedKind = selectedKind,
                online = online,
                queueCount = queueItems.size,
                textValues = textValues,
                imageValues = imageValues,
                message = captureMessage,
                messageError = captureError,
                saving = captureBusy || !draftReady || account == null,
                draftStatus = draftStatus,
                onRemoveImage = { key -> imageValues[key] = "" },
                scrollRequest = scrollRequest,
                onKindSelected = { clearCurrentForm(); selectedKind = it; scrollRequest = null; captureMessage = "" },
                onTextChanged = { key, value -> textValues[key] = value },
                onCaptureImage = { field ->
                    val photoFile = createTempPhotoFile(context)
                    activeImageField = field
                    pendingImagePath = photoFile.absolutePath
                    cameraLauncher.launch(photoFile.toContentUri(context))
                },
                onSaveOffline = {
                    if (account?.can(selectedKind.name.lowercase() + ".capture") != true) {
                        captureError = true; captureMessage = "Your role cannot capture this record type. Your draft is kept. Contact an administrator."; return@CaptureScreen
                    }
                    if (captureBusy) return@CaptureScreen
                    val validationError = firstValidationError(schema, textValues, imageValues)
                    if (validationError != null) {
                        captureError = true
                        captureMessage = validationError.message
                        scrollRequest = ScrollRequest(
                            fieldKey = validationError.fieldKey,
                            requestId = System.nanoTime()
                        )
                        return@CaptureScreen
                    }

                    captureError = false
                    captureBusy = true
                    val payload = linkedMapOf<String, String>()
                    schema.textFields.forEach { payload[it.key] = textValues[it.key].orEmpty().trim() }
                    schema.imageFields.forEach { payload[it.key] = imageValues[it.key].orEmpty() }

                    if (selectedKind == CaptureKind.PED) {
                        listOf("inspection_voyage_id", "vessel_name", "voyage_number").forEach { key -> textValues[key]?.takeIf { it.isNotBlank() }?.let { payload[key] = it } }
                    }
                    val titleValue = payload[schema.titleKey].orEmpty().ifBlank { schema.label }
                    val item = PendingCapture(
                        id = queueStore.newCaptureId(),
                        kind = selectedKind,
                        endpoint = schema.endpoint,
                        title = "${schema.label}: $titleValue",
                        createdAt = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                        payload = payload
                    )

                    draft.viewModelScope.launch {
                        try {
                            val updated = withContext(Dispatchers.IO) { queueStore.add(item); queueStore.loadQueue() }
                            queueItems.clear(); queueItems.addAll(updated)
                            clearCurrentForm()
                            withContext(Dispatchers.IO) { queueStore.clearDraft() }
                            draft.queueRevision.value++
                            scrollRequest = null
                            captureMessage = "Capture saved on this device. Check Uploads for sync status."
                            AutoSync.schedule(context)
                        } catch (e: Exception) {
                            captureError = true
                            captureMessage = "Could not save this capture. Your entries are still here. ${e.message.orEmpty()}"
                        } finally { captureBusy = false }
                    }
                },
                onReset = {
                    clearCurrentForm()
                    scrollRequest = null
                    captureMessage = ""
                },
                onGoToSync = { screen = AppScreen.SYNC },
                onPreInspection = { screen = AppScreen.INSPECTION },
            )

            AppScreen.SYNC -> SyncScreen(
                canCapture = account.canCaptureEvidence(), canInspect = account.canViewTally(),
                modifier = Modifier.padding(padding),
                queue = queueItems.filter { account.canCapture(it.kind) },
                busy = syncBusy,
                message = syncMessage,
                messageError = syncError,
                online = online,
                history = if (account.canCaptureEvidence()) receipts else emptyList(),
                tally = if (account.canViewTally()) tallyRecords else emptyList(),
                legacyCount = if (account.canCaptureEvidence()) legacyCount else 0,
                onOpenTally = { screen = AppScreen.INSPECTION },
                onReview = { id -> scope.launch { reviewCapture = withContext(Dispatchers.IO) { queueStore.loadCaptureForSync(id) } } },
                onRetry = { id -> uploadOne = id },
                onSignIn = { loginEmail = account?.email.orEmpty(); loginPassword = ""; screen = AppScreen.LOGIN },
                onGoToCapture = { screen = account.homeScreen() },
                onRefresh = {
                    refreshQueue()
                    syncError = false
                    syncMessage = "Queue refreshed."
                    scope.launch { receipts = withContext(Dispatchers.IO) { queueStore.history() }; tallyRecords = owner?.let { withContext(Dispatchers.IO) { repository.store.list(it) } } ?: emptyList() }
                },
                onSyncAll = { syncCaptures() }
            )
        }
    }
}

internal data class ScrollRequest(
    val fieldKey: String,
    val requestId: Long
)

private data class ValidationError(
    val fieldKey: String,
    val message: String
)

private fun firstValidationError(
    schema: CaptureSchema,
    textValues: Map<String, String>,
    imageValues: Map<String, String>
): ValidationError? {
    schema.textFields.forEach { field ->
        if (field.required && textValues[field.key].orEmpty().trim().isEmpty()) {
            return ValidationError(
                fieldKey = field.key,
                message = "${field.label} is required."
            )
        }
    }

    schema.imageFields.forEach { field ->
        if (field.required && imageValues[field.key].orEmpty().trim().isEmpty()) {
            return ValidationError(
                fieldKey = field.key,
                message = "${field.label} is required."
            )
        }
    }

    return null
}

private fun Bitmap.toDataUrl(): String {
    val buffer = ByteArrayOutputStream()
    compress(CompressFormat.JPEG, 85, buffer)
    val encoded = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
    return "data:image/jpeg;base64,$encoded"
}

private fun Context.optimizedDataUrlFromFile(path: String): String? {
    val sourceFile = File(path)
    if (!sourceFile.exists()) {
        return null
    }

    return try {
        val decoded = decodeSampledBitmap(sourceFile.absolutePath, 2200) ?: return null
        val rotated = decoded.rotateFromExif(sourceFile.absolutePath)
        val base = rotated.scaleDownTo(1600)

        val targetSizes = listOf(1600, 1280, 1024, 900)
        val qualities = listOf(88, 82, 76, 70, 64, 58, 52, 46)

        for (targetSize in targetSizes) {
            val candidate = base.scaleDownTo(targetSize)
            for (quality in qualities) {
                val bytes = candidate.toJpegBytes(quality)
                if (bytes.size <= 200 * 1024) {
                    val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
                    return "data:image/jpeg;base64,$encoded"
                }
            }
        }

        val fallback = base.scaleDownTo(900).toJpegBytes(42)
        val encoded = Base64.encodeToString(fallback, Base64.NO_WRAP)
        "data:image/jpeg;base64,$encoded"
    } finally {
        sourceFile.delete()
    }
}

private fun decodeSampledBitmap(path: String, maxDimension: Int): Bitmap? {
    val options = BitmapFactory.Options().apply {
        inJustDecodeBounds = true
    }
    BitmapFactory.decodeFile(path, options)

    val largestSide = maxOf(options.outWidth, options.outHeight).coerceAtLeast(1)
    var sampleSize = 1
    while (largestSide / sampleSize > maxDimension) {
        sampleSize *= 2
    }

    val decodeOptions = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    return BitmapFactory.decodeFile(path, decodeOptions)
}

private fun Bitmap.rotateFromExif(path: String): Bitmap {
    val exif = runCatching { ExifInterface(path) }.getOrNull() ?: return this
    val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> 0f
    }

    if (degrees == 0f) {
        return this
    }

    val matrix = Matrix().apply {
        postRotate(degrees)
    }

    return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
}

private fun Bitmap.scaleDownTo(maxDimension: Int): Bitmap {
    val largestSide = maxOf(width, height)
    if (largestSide <= maxDimension) {
        return this
    }

    val ratio = maxDimension.toFloat() / largestSide.toFloat()
    val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
    val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true)
}

private fun Bitmap.toJpegBytes(quality: Int): ByteArray {
    val buffer = ByteArrayOutputStream()
    compress(CompressFormat.JPEG, quality, buffer)
    return buffer.toByteArray()
}

private fun createTempPhotoFile(context: android.content.Context): File {
    val cameraDir = File(context.cacheDir, "camera").apply {
        mkdirs()
    }
    return File.createTempFile("ped_capture_", ".jpg", cameraDir)
}

private fun File.toContentUri(context: android.content.Context): Uri {
    return FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        this
    )
}

internal fun String.decodeBitmap(): Bitmap? {
    if (startsWith("pedfile:")) return runCatching {
        val path = removePrefix("pedfile:")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        val options = BitmapFactory.Options()
        while (bounds.outWidth / options.inSampleSize > 1800 || bounds.outHeight / options.inSampleSize > 1800) options.inSampleSize *= 2
        BitmapFactory.decodeFile(path, options)
    }.getOrNull()
    val base64 = substringAfter(',', "")
    if (base64.isBlank()) {
        return null
    }

    return runCatching {
        val bytes = Base64.decode(base64, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options()
        while (bounds.outWidth / options.inSampleSize > 900 || bounds.outHeight / options.inSampleSize > 900) options.inSampleSize *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()
}

internal fun extractVinSerialValue(rawText: String): String? {
    if (rawText.isBlank()) {
        return null
    }

    val lines = rawText
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toList()

    val labelIndexes = lines.mapIndexedNotNull { index, line ->
        if (line.contains("VIN", ignoreCase = true) && line.contains("SERIAL", ignoreCase = true)) {
            index
        } else {
            null
        }
    }
    if (labelIndexes.isEmpty()) {
        return null
    }

    labelIndexes.forEach { labelIndex ->
        val labelLine = lines[labelIndex]
        val inlineArea = labelLine.replace(Regex("(?i)VIN\\s*/\\s*SERIAL\\s*[:#-]?"), " ").trim()
        extractVinLikeFromArea(inlineArea)?.let { return it }
        val nextLine = lines.getOrNull(labelIndex + 1).orEmpty()
        extractVinLikeFromArea(nextLine)?.let { return it }
    }

    return null
}

private fun extractVinLikeFromArea(rawArea: String): String? {
    if (rawArea.isBlank()) {
        return null
    }

    val sanitized = rawArea
        .uppercase(Locale.getDefault())
        .replace(Regex("(?i)BOOKING|CARRIER|VESSEL|POL|RCVD|D/R#?|DR#?|TYPE"), " ")
        .replace(Regex("[^A-Z0-9\\s-]"), " ")

    val parts = Regex("[A-Z0-9]+")
        .findAll(sanitized)
        .map { it.value }
        .toList()
    if (parts.isEmpty()) {
        return null
    }

    val candidates = mutableListOf<String>()
    for (start in parts.indices) {
        var merged = ""
        for (index in start until parts.size) {
            merged += parts[index]
            if (merged.length > 25) {
                break
            }

            if (merged.length in 8..25 && merged.any(Char::isLetter) && merged.any(Char::isDigit)) {
                candidates += merged
            }
        }
    }
    if (candidates.isEmpty()) {
        return null
    }

    return candidates.firstOrNull { it.length == 17 } ?: candidates.maxByOrNull { it.length }
}

internal fun extractChassisCandidate(rawText: String): String? {
    if (rawText.isBlank()) {
        return null
    }

    val tokenCandidates = rawText
        .uppercase(Locale.getDefault())
        .split(Regex("[^A-Z0-9]+"))
        .map { normalizeChassisValue(it) }
        .filter { candidate ->
            candidate.length in 8..25 &&
                candidate.any(Char::isLetter) &&
                candidate.any(Char::isDigit)
        }

    val lineCandidates = rawText
        .lineSequence()
        .map { normalizeChassisValue(it) }
        .filter { candidate ->
            candidate.length in 8..25 &&
                candidate.any(Char::isLetter) &&
                candidate.any(Char::isDigit)
        }
        .toList()

    val allCandidates = (tokenCandidates + lineCandidates).distinct()
    if (allCandidates.isEmpty()) {
        return null
    }

    val vinLike = allCandidates.firstOrNull { candidate ->
        candidate.length == 17 &&
            candidate.none { it == 'I' || it == 'O' || it == 'Q' }
    }
    return vinLike ?: allCandidates.maxByOrNull { it.length }
}

private fun normalizeChassisValue(raw: String): String {
    return raw.uppercase(Locale.getDefault())
        .replace("\\s+".toRegex(), "")
        .replace(Regex("[^A-Z0-9-]"), "")
}

private tailrec fun Context.findActivity(): Activity? {
    return when (this) {
        is Activity -> this
        is android.content.ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}

private fun playSyncNotification(success: Boolean) {
    val toneType = if (success) {
        ToneGenerator.TONE_PROP_ACK
    } else {
        ToneGenerator.TONE_PROP_NACK
    }

    runCatching {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
        try {
            tone.startTone(toneType, 220)
        } finally {
            tone.release()
        }
    }
}
