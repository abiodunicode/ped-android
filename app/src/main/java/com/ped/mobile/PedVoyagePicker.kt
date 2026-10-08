package com.ped.mobile

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ped.mobile.inspection.Voyage
import com.ped.mobile.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Account/server-scoped cache. Directory refresh never modifies a saved capture or draft selection. */
internal class PedVoyageCache(context: Context, private val owner: String) {
    private val prefs = context.getSharedPreferences("ped-voyages", Context.MODE_PRIVATE)
    fun read(): List<Voyage> = runCatching {
        val array = JSONArray(prefs.getString("$owner:directory", "[]"))
        (0 until array.length()).map { Voyage.read(array.getJSONObject(it)) }
    }.getOrDefault(emptyList())
    fun selected(): Voyage? = runCatching { Voyage.read(JSONObject(prefs.getString("$owner:selected", "")!!)) }.getOrNull()
    fun select(voyage: Voyage?) { check(prefs.edit().putString("$owner:selected", voyage?.json()?.toString()).commit()) { "Could not remember voyage on this device." } }
    fun refresh(apiBase: String, token: String): List<Voyage> {
        val api = ApiClient(apiBase)
        val all = mutableListOf<Voyage>()
        var page = 1
        do {
            val response = api.inspectionRequest(token, "/ped-voyages?page=$page")
            val data = response.getJSONArray("data")
            for (i in 0 until data.length()) all += Voyage.read(data.getJSONObject(i))
            page++
        } while (page <= response.getInt("last_page"))
        check(prefs.edit().putString("$owner:directory", JSONArray(all.map { it.json() }).toString()).commit()) { "Could not save voyages for offline use." }
        return all
    }
}

@Composable internal fun PedVoyagePicker(owner: String, token: String, apiBase: String, online: Boolean, enabled: Boolean,
    values: Map<String, String>, onChange: (String, String) -> Unit) {
    val context = LocalContext.current
    val cache = remember(owner) { PedVoyageCache(context.applicationContext, owner) }
    val scope = rememberCoroutineScope()
    var voyages by remember(owner) { mutableStateOf(emptyList<Voyage>()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    var vessel by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    fun apply(v: Voyage?) {
        onChange("inspection_voyage_id", v?.id?.toString().orEmpty())
        onChange("vessel_name", v?.vessel.orEmpty())
        onChange("voyage_number", v?.voyage.orEmpty())
        scope.launch { try { withContext(Dispatchers.IO) { cache.select(v) } } catch (e: Exception) { error = e.message.orEmpty() } }
    }
    fun refresh() {
        if (busy) return
        busy = true; error = ""
        scope.launch {
            try { voyages = withContext(Dispatchers.IO) { cache.refresh(apiBase, token) } }
            catch (e: Exception) { error = if (voyages.isEmpty()) e.message.orEmpty() else "Could not refresh. Downloaded voyages are still available offline." }
            finally { busy = false }
        }
    }
    LaunchedEffect(owner) {
        voyages = withContext(Dispatchers.IO) { cache.read() }
        if (!values.containsKey("inspection_voyage_id")) {
            val selected = withContext(Dispatchers.IO) { cache.selected() }
            if (selected != null) apply(selected)
        }
        if (online) refresh()
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Vessel & voyage", style = MaterialTheme.typography.titleMedium)
            if (!values["inspection_voyage_id"].isNullOrBlank()) {
                Text(values["vessel_name"].orEmpty(), style = MaterialTheme.typography.titleLarge)
                Text("Voyage ${values["voyage_number"].orEmpty()}")
            } else Text("Not assigned · optional")
            Text("This selection is saved with each CIMS capture. Previously saved captures stay unchanged.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { choosing = true; vessel = ""; search = "" }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (values["inspection_voyage_id"].isNullOrBlank()) "Choose vessel & voyage" else "Change voyage")
            }
            TextButton(onClick = { refresh() }, enabled = enabled && online && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (busy) "Downloading voyages…" else "Refresh voyages (${voyages.size} saved)") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text(if (vessel.isBlank()) "Choose vessel" else "$vessel · choose voyage") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(search, { search = it }, label = { Text(if (vessel.isBlank()) "Search vessels" else "Search voyages") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    if (voyages.isEmpty()) Text("Connect and refresh voyages before going offline. Only administrators can add voyages.")
                    if (vessel.isBlank()) voyages.map { it.vessel }.distinct().filter { it.contains(search, true) }.forEach { name ->
                        TextButton(onClick = { vessel = name; search = "" }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(name) }
                    } else voyages.filter { it.vessel == vessel && it.voyage.contains(search, true) }.forEach { v ->
                        TextButton(onClick = { apply(v); choosing = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Voyage ${v.voyage}") }
                    }
                }
                if (vessel.isNotBlank()) TextButton(onClick = { vessel = ""; search = "" }) { Text("Back to vessels") }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } },
        dismissButton = { TextButton(onClick = { apply(null); choosing = false }) { Text("Leave unassigned") } })
}
