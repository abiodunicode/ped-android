package com.ped.mobile.inspection

import androidx.activity.compose.BackHandler
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.ped.mobile.BuildConfig
import com.ped.mobile.sync.AutoSync
import com.ped.mobile.sync.SyncCoordinator
import com.ped.mobile.network.isOnline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private fun localTime(value: String) = runCatching {
    DateTimeFormatter.ofPattern("dd MMM, HH:mm").withZone(ZoneId.systemDefault()).format(Instant.parse(value))
}.getOrDefault(value)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InspectionScreen(token: String, onExit: () -> Unit, apiBase: String = BuildConfig.API_BASE_URL, onNavigate: ((com.ped.mobile.data.AppScreen) -> Unit)? = null, capturePending: Int = 0) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember(apiBase) { InspectionRepository.get(context, apiBase) }
    var account by remember { mutableStateOf<InspectionAccount?>(null) }
    var catalogueCount by remember { mutableStateOf(0) }
    var directory by remember { mutableStateOf<InspectionDirectory?>(null) }
    var records by remember { mutableStateOf(emptyList<LocalTally>()) }
    val syncState by SyncCoordinator.status.collectAsState()
    var busy by remember { mutableStateOf(false) }
    var booting by remember { mutableStateOf(true) }
    var online by remember { mutableStateOf(isOnline(context)) }
    var message by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val draftPrefs = remember { context.getSharedPreferences("inspection-drafts", android.content.Context.MODE_PRIVATE) }
    var draftLoaded by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf("voyages") }
    var search by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var form by rememberSaveable { mutableStateOf("{}") }
    var baseline by rememberSaveable { mutableStateOf("{}") }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var readOnly by rememberSaveable { mutableStateOf(false) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var deleteItem by remember { mutableStateOf<LocalTally?>(null) }
    val voyage = selected?.let { Voyage.read(JSONObject(it)) }
    val pending = records.count { it.state != "synced" }
    suspend fun reload() {
        val current = account ?: return
        withContext(Dispatchers.IO) {
            repository.store.directory(repository.owner(current)) to repository.store.list(repository.owner(current))
        }.let { directory = it.first; records = it.second }
        catalogueCount = withContext(Dispatchers.IO) { repository.catalogue.count(repository.owner(current)) }
    }
    LaunchedEffect(syncState.revision) { if (account?.let(repository::owner) == syncState.owner) reload() }
    fun back() {
        if (busy) return
        if (selected != null) {
            if (!readOnly && form != baseline) discard = true
            else { selected = null; error = "" }
        } else onExit()
    }
    fun prepare() {
        busy = true; error = ""
        scope.launch {
            try {
                val current = withContext(Dispatchers.IO) { repository.verifyAccount(token) }
                account = current
                reload()
                withContext(Dispatchers.IO) { repository.refresh(token, current) }
                reload()
                message = "Voyages and $catalogueCount vehicle models saved on this device. You can now capture offline."
            } catch (e: Exception) { error = e.message ?: "Unable to prepare offline capture." }
            finally { busy = false }
        }
    }
    LaunchedEffect(token) {
        try {
            account = withContext(Dispatchers.IO) { repository.cachedAccount(token) }
            reload()
        } catch (e: Exception) { error = e.message ?: "Unable to read saved tally." }
        finally { booting = false }
    }
    LaunchedEffect(Unit) {
        while (true) { online = isOnline(context); delay(2000) }
    }
    LaunchedEffect(token, online) {
        if (online) try { account = withContext(Dispatchers.IO) { repository.verifyAccount(token) } } catch (_: Exception) { }
    }
    LaunchedEffect(account) {
        val actor = account ?: return@LaunchedEffect
        val raw = draftPrefs.getString(repository.owner(actor), null)
        if (selected == null && raw != null) runCatching {
            val saved = JSONObject(raw)
            selected = saved.getString("voyage"); form = saved.getString("form"); baseline = saved.optString("baseline", "{}")
            editingId = saved.optString("editingId").ifBlank { null }; readOnly = false
            message = "Draft restored on this device. Review and save when ready."
        }
        draftLoaded = true
    }
    LaunchedEffect(selected, form, readOnly, draftLoaded) {
        val actor = account ?: return@LaunchedEffect
        if (!draftLoaded) return@LaunchedEffect
        val key = repository.owner(actor)
        val value = if (selected != null && !readOnly) JSONObject().put("voyage", selected).put("form", form).put("baseline", baseline).put("editingId", editingId ?: "").toString() else null
        try { withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { check(draftPrefs.edit().putString(key, value).commit()) } }
        catch (e: Exception) { error = "Could not protect this draft. Keep the screen open and save the vehicle." }
    }
    BackHandler { back() }

    InspectionTheme {
        com.ped.mobile.PedWorkspace(
            screen = com.ped.mobile.data.AppScreen.INSPECTION, account = account, online = online,
            pendingCount = capturePending + pending, busy = busy,
            onNavigate = { destination -> onNavigate?.invoke(destination) ?: onExit() },
            subtitle = if (voyage == null) "Choose voyage · capture · review" else if (readOnly) "Saved vehicle" else "Vehicle tally",
            onBack = if (voyage != null) ({ back() }) else null
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).imePadding(), contentAlignment = Alignment.TopCenter) {
                if (voyage != null) {
                    if (readOnly) SavedVehicleSummary(voyage, JSONObject(form), records.find { it.id == editingId }, onBack = { selected = null }, onEdit = { if (account?.can("inspections.create") == true) readOnly = false else error = "Your role cannot edit captured tallies. Saved vehicles are kept." })
                    else VehicleFormScreen(voyage, form, directory?.makes.orEmpty(), busy, readOnly, showErrors, message, error,
                        catalogueSearch = { query -> account?.let { repository.catalogue.search(repository.owner(it), query) }.orEmpty() },
                        onChange = { form = it; error = ""; message = "" },
                        onSave = { next ->
                            if (account?.can("inspections.create") != true) {
                                error = "Your role cannot capture inspections. Your draft is kept. Contact an administrator."
                            } else {
                            showErrors = true
                            if (validationErrors(JSONObject(form)).isEmpty()) {
                                busy = true; error = ""
                                scope.launch {
                                    try {
                                        val current = requireNotNull(account)
                                        val latest = directory?.voyages?.firstOrNull { it.id == voyage.id } ?: voyage
                                        withContext(Dispatchers.IO) { repository.store.save(repository.owner(current), latest, JSONObject(form), editingId) }
                                        reload()
                                        message = "Vehicle saved on this device. Check Uploads for sync status."
                                        AutoSync.schedule(context)
                                        showErrors = false; editingId = null
                                        form = "{}"; baseline = "{}"
                                        if (!next) { selected = null; tab = "saved" }
                                    } catch (e: Exception) { error = e.message ?: "Could not save. Your entries are still here." }
                                    finally { busy = false }
                                }
                            }
                            }
                        })
                } else LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = tab == "voyages", onClick = { tab = "voyages" }, label = { Text("Voyages") }, modifier = Modifier.heightIn(min = 48.dp))
                        FilterChip(selected = tab == "saved", onClick = { tab = "saved" }, label = { Text("Saved tally ($pending)") }, modifier = Modifier.heightIn(min = 48.dp))
                    } }
                    item {
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("CIMS · BEFORE DISCHARGE", style = MaterialTheme.typography.labelLarge)
                                Text(if (tab == "voyages") "Ready for the next vehicle." else "$pending waiting to sync", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                Text(if (tab == "voyages") "Choose a prepared voyage. Save each vehicle on this device, then sync when you’re connected." else "Saved vehicles stay here until the server confirms receipt. Automatic sync runs when connected. Manual sync remains available in Uploads.")
                                Text(if (online) "Network available" else "Offline · capture is available", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                    if (booting || busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                    if (message.isNotBlank()) item { Notice(message) }
                    if (error.isNotBlank()) item { Notice(error, true) }
                    if (account == null) item {
                        Section("Prepare this device") {
                            Text("Connect once to confirm your inspector account and download administrator-created voyages. Keep Remember me enabled at sign-in to reopen the app offline.")
                            Button(onClick = ::prepare, enabled = !busy && !booting && online, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Prepare offline capture") }
                        }
                    } else {
                        item { Text(account!!.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        if (tab == "voyages") {
                            item {
                                Section("Offline directory") {
                                    Text(directory?.let { "Updated ${localTime(it.updatedAt)} · ${it.voyages.size} voyages" } ?: "No voyages downloaded yet.", style = MaterialTheme.typography.bodyMedium)
                                    OutlinedButton(onClick = ::prepare, enabled = !busy && online, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (directory == null) "Prepare offline capture" else "Refresh directory") }
                                    Text("$catalogueCount vehicle models available offline. Refresh to receive new makes, models and voyages before leaving for an inspection.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    OutlinedTextField(search, { search = it }, label = { Text("Search vessel or voyage") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                }
                            }
                            val matches = directory?.voyages.orEmpty().filter { "${it.vessel} ${it.voyage}".contains(search, ignoreCase = true) }
                            if (matches.isEmpty()) item { Text(if (directory == null) "Download voyages while online to begin." else "No matching voyages. Ask an administrator to add your voyage, then refresh.") }
                            items(matches, key = { it.id }) { item ->
                                Section(item.vessel) {
                                    Text("Voyage ${item.voyage}", style = MaterialTheme.typography.titleMedium)
                                    Text(if (item.completed) "Completed · read-only on the web" else "Available for offline capture", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = {
                                        selected = item.json().toString(); form = "{}"; baseline = "{}"; editingId = null; readOnly = false; showErrors = false; error = ""; message = ""
                                    }, enabled = !busy && !item.completed && account?.can("inspections.create") == true, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Start tally") }
                                }
                            }
                        } else {
                            item {
                                if (onNavigate != null) {
                                    Button(onClick = { onNavigate(com.ped.mobile.data.AppScreen.SYNC) }, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("View uploads ($pending)") }
                                } else Button(onClick = {
                                    busy = true; error = ""; message = "Preparing sync…"
                                    scope.launch {
                                        try {
                                            val current = requireNotNull(account)
                                            val outcome = withContext(Dispatchers.IO) {
                                                val result = runCatching {
                                                    repository.sync(token, current) { index, total ->
                                                        withContext(Dispatchers.Main.immediate) { message = "Syncing $index of $total…" }
                                                    }
                                                }
                                                result to repository.store.list(repository.owner(current))
                                            }
                                            records = outcome.second
                                            outcome.first.onSuccess { message = it }.onFailure { error = it.message ?: "Sync failed. Your saved vehicles are safe."; message = "" }
                                        } catch (e: Exception) { error = e.message ?: "Sync failed. Your saved vehicles are safe." }
                                        finally { busy = false }
                                    }
                                }, enabled = !busy && online && pending > 0, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "Syncing…" else "Sync $pending saved ${if (pending == 1) "vehicle" else "vehicles"}") }
                            }
                            if (records.isEmpty()) item { Text("No vehicles saved on this device for this account. Choose a voyage to start your tally.") }
                            items(records, key = { it.id }) { record ->
                                Section(record.data.text("chassis_number")) {
                                    Text("${record.data.text("vehicle_make")} · ${record.voyage.vessel} / ${record.voyage.voyage}")
                                    Text(when (record.state) { "synced" -> "Uploaded · ${record.reportNumber}"; "pending" -> "Saved on device · not sent"; else -> "Needs attention · ${record.recoveryAction}" }, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
                                    Text("Captured ${localTime(record.createdAt)}", style = MaterialTheme.typography.bodyMedium)
                                    if (record.error.isNotBlank()) Notice(record.error, true)
                                    if (record.state == "attempted") Text("The original submission is kept unchanged for a safe retry. If the report is closed or the chassis already exists, ask an administrator to review it.", style = MaterialTheme.typography.bodyMedium)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = {
                                            selected = record.voyage.json().toString(); form = record.payload; baseline = form; editingId = record.id; readOnly = true; showErrors = false; error = ""; message = ""
                                        }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("View vehicle") }
                                        if (record.editable) TextButton(onClick = { deleteItem = record }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Remove") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("Discard unsaved changes?") }, text = { Text("Save this vehicle on the device before leaving to keep your changes.") }, confirmButton = { TextButton(onClick = { selected = null; discard = false }) { Text("Discard changes") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Keep editing") } })
        deleteItem?.let { item -> AlertDialog(onDismissRequest = { deleteItem = null }, title = { Text("Remove unsent vehicle?") }, text = { Text("${item.data.text("chassis_number")} has never been sent. Removing it deletes this local capture.") }, confirmButton = { TextButton(onClick = {
            deleteItem = null
            scope.launch { try { withContext(Dispatchers.IO) { repository.store.deleteUnsent(repository.owner(requireNotNull(account)), item.id) }; reload() } catch (e: Exception) { error = e.message.orEmpty() } }
        }) { Text("Remove vehicle") } }, dismissButton = { TextButton(onClick = { deleteItem = null }) { Text("Keep vehicle") } }) }
    }
}

@Composable private fun Notice(text: String, isError: Boolean = false) {
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite }, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer, style = MaterialTheme.typography.bodyMedium)
    }
}
@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable private fun VehicleFormScreen(voyage: Voyage, serialized: String, makes: List<String>, busy: Boolean, readOnly: Boolean, showErrors: Boolean, message: String, error: String, catalogueSearch: (String) -> List<String>, onChange: (String) -> Unit, onSave: (Boolean) -> Unit) {
    var scanning by remember { mutableStateOf(false) }
    val form = remember(serialized) { JSONObject(serialized) }
    var makeExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val inputPreferences = remember { context.getSharedPreferences("ped-ui-preferences", android.content.Context.MODE_PRIVATE) }
    var useMakeDropdown by remember { mutableStateOf(inputPreferences.getBoolean("vehicle-make-dropdown", true)) }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    var catalogueSuggestions by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(form.text("vehicle_make")) {
        val query = form.text("vehicle_make")
        catalogueSuggestions = emptyList()
        if (query.trim().length >= 2) {
            delay(150)
            catalogueSuggestions = withContext(Dispatchers.IO) { catalogueSearch(query) }
        }
    }
    val errors = if (showErrors) validationErrors(form) else emptyMap()
    val scroll = rememberScrollState()
    var previousBlank by remember { mutableStateOf(form.length() == 0) }
    LaunchedEffect(serialized) {
        val blank = form.length() == 0
        if (blank && !previousBlank) {
            makeExpanded = false
            focusManager.clearFocus()
            keyboard?.hide()
            scroll.scrollTo(0)
        }
        previousBlank = blank
    }
    fun change(key: String, value: Any?) { onChange(JSONObject(serialized).put(key, value ?: JSONObject.NULL).toString()) }
    val offsets = remember { mutableStateMapOf<String, Int>() }
    val fieldFocus = remember { listOf("vehicle_make", "chassis_number", "deck", "vip_key_number", "remarks").associateWith { FocusRequester() } }
    LaunchedEffect(showErrors, error) { errors.keys.firstOrNull()?.let { key ->
        offsets[key]?.let { y -> scroll.animateScrollTo(y.coerceAtLeast(0)) }
        fieldFocus[key]?.requestFocus()
    } }
    Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(scroll).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("${voyage.vessel} · ${voyage.voyage}", style = MaterialTheme.typography.titleMedium)
        Text(if (readOnly) "Saved submission · read-only" else "Offline capture · save each vehicle before continuing", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.isNotBlank()) Notice(message)
        if (error.isNotBlank()) Notice(error, true)
        if (errors.isNotEmpty()) Notice("Check ${errors.size} highlighted field(s). Your entries have been kept.", true)
        Section("01 · Vehicle identity") {
            Text("Search make / model or enter a cargo type. Suggestions work offline after preparing this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val query = form.text("vehicle_make")
            val makeChoices = (listOf("General Cargo") + makes).distinct().filter { query.isBlank() || it.contains(query, true) }
            val choices = (catalogueSuggestions + makeChoices).distinct().take(40)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(
                    value = useMakeDropdown, enabled = !busy && !readOnly, role = Role.Switch,
                    onValueChange = { enabled ->
                        makeExpanded = false
                        focusManager.clearFocus()
                        keyboard?.hide()
                        useMakeDropdown = enabled
                        inputPreferences.edit().putBoolean("vehicle-make-dropdown", enabled).apply()
                    }
                ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Use dropdown", style = MaterialTheme.typography.bodyLarge)
                    Text(if (useMakeDropdown) "Dropdown selected" else "Text entry selected", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = useMakeDropdown, onCheckedChange = null, enabled = !busy && !readOnly)
            }
            if (!useMakeDropdown) {
                OutlinedTextField(
                    value = query, onValueChange = { change("vehicle_make", it) },
                    label = { Text("Vehicle make *") },
                    placeholder = { Text("Enter make / model or cargo type") },
                    modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus.getValue("vehicle_make")).onGloballyPositioned { offsets["vehicle_make"] = it.positionInRoot().y.toInt() + scroll.value - 140 },
                    singleLine = true, readOnly = readOnly, enabled = !busy,
                    isError = errors.containsKey("vehicle_make"),
                    supportingText = { errors["vehicle_make"]?.let { Text(it) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                if (query.isNotBlank() && !readOnly) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        choices.filter { !it.equals(query, true) }.take(6).forEach { choice ->
                            SuggestionChip(onClick = { change("vehicle_make", choice) }, label = { Text(choice) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
            } else ExposedDropdownMenuBox(
                expanded = makeExpanded && !busy && !readOnly,
                onExpandedChange = { if (!busy && !readOnly) makeExpanded = !makeExpanded }
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { change("vehicle_make", it); makeExpanded = true },
                    label = { Text("Vehicle make *") },
                    placeholder = { Text("Choose or search make / model") },
                    modifier = Modifier.menuAnchor().fillMaxWidth().focusRequester(fieldFocus.getValue("vehicle_make")).onGloballyPositioned { offsets["vehicle_make"] = it.positionInRoot().y.toInt() + scroll.value - 140 },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = makeExpanded) },
                    singleLine = true, readOnly = readOnly, enabled = !busy,
                    isError = errors.containsKey("vehicle_make"),
                    supportingText = { Text(errors["vehicle_make"] ?: "Choose a make, search a model, or type another vehicle / cargo type.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
                )
                // Keep keyboard and backspace events on the editable anchor while suggestions are open.
                DropdownMenu(expanded = makeExpanded && !busy && !readOnly,
                    onDismissRequest = { makeExpanded = false },
                    properties = androidx.compose.ui.window.PopupProperties(focusable = false),
                    modifier = Modifier.exposedDropdownSize().heightIn(max = 280.dp)) {
                    choices.forEach { choice ->
                        DropdownMenuItem(text = { Text(choice) }, onClick = {
                            change("vehicle_make", choice)
                            makeExpanded = false
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                    if (query.isNotBlank() && choices.none { it.equals(query.trim(), true) }) {
                        DropdownMenuItem(text = { Text("Use \"${query.trim()}\" as entered") }, onClick = {
                            change("vehicle_make", query.trim())
                            makeExpanded = false
                            focusManager.clearFocus()
                            keyboard?.hide()
                        }, modifier = Modifier.heightIn(min = 48.dp))
                    }
                }
            }
            for ((key, label) in linkedMapOf("chassis_number" to "Chassis number *", "deck" to "Deck", "vip_key_number" to "VIP key number")) {
                OutlinedTextField(form.text(key), { change(key, it) }, textStyle = if (key == "chassis_number") com.ped.mobile.ui.PedChassisTextStyle else MaterialTheme.typography.bodyLarge, label = { Text(label) }, modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus.getValue(key)).onGloballyPositioned { offsets[key] = it.positionInRoot().y.toInt() + scroll.value - 140 }, singleLine = true, readOnly = readOnly, enabled = !busy, isError = errors.containsKey(key), supportingText = { errors[key]?.let { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = if (key == "deck") KeyboardType.Number else KeyboardType.Text, capitalization = if (key == "chassis_number") KeyboardCapitalization.Characters else KeyboardCapitalization.Words))
                if (key == "chassis_number" && !readOnly) {
                    ChassisPhotoScanner(enabled = !busy, onUseNumber = { change("chassis_number", it) }, onBusyChange = { scanning = it })
                }
            }
        }

        Section("02 · Keys & sensors") {
            Text("Leave unanswered if not checked. Zero means you explicitly observed none.", style = MaterialTheme.typography.bodyMedium)
            for ((key, label) in mapOf("key_count" to "Keys", "sensor_count" to "Sensors")) {
                Text(label, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("" to "Not recorded", "0" to "None (0)", "1" to "1", "2" to "2", "3" to "3").forEach { (value, text) ->
                        FilterChip(selected = form.text(key) == value, onClick = { change(key, value.toIntOrNull()) }, label = { Text(text) }, enabled = !busy && !readOnly, modifier = Modifier.heightIn(min = 48.dp).semantics { this.stateDescription = "$label: $text" })
                    }
                }
            }
        }
        Section("03 · Condition & accessories") {
            Text("YES = observed / present. NO = explicitly not observed / present.", style = MaterialTheme.typography.bodyMedium)
            observationFields.forEach { (key, label) ->
                Text(label, fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "Not recorded", true to "YES", false to "NO").forEach { (value, text) ->
                        val current = if (form.isNull(key)) null else form.getBoolean(key)
                        FilterChip(selected = current == value, onClick = { change(key, value) }, label = { Text(text) }, enabled = !busy && !readOnly, modifier = Modifier.heightIn(min = 48.dp).semantics { this.stateDescription = "$label: $text" })
                    }
                }
                HorizontalDivider()
            }
        }
        Section("04 · Remarks") {
            OutlinedTextField(form.text("remarks"), { change("remarks", it) }, label = { Text("Damage, missing items or other observations") }, modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus.getValue("remarks")), minLines = 3, readOnly = readOnly, enabled = !busy, isError = errors.containsKey("remarks"), supportingText = { Text(errors["remarks"] ?: "Optional · ${form.text("remarks").length}/4000 characters") })
        }
        if (!readOnly) {
          Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(true) }, enabled = !busy && !scanning, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer)) { Text(if (busy) "Saving…" else "Save & next vehicle") }
            OutlinedButton(onClick = { onSave(false) }, enabled = !busy && !scanning, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Save & review tally") }
            Text("Saved on device first · upload from Uploads", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
          }
        }
    }
}
