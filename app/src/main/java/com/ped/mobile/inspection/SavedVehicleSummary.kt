package com.ped.mobile.inspection

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONObject

@Composable internal fun SavedVehicleSummary(voyage: Voyage, data: JSONObject, record: LocalTally?, onBack: () -> Unit, onEdit: () -> Unit) {
    Column(Modifier.widthIn(max = 720.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TextButton(onClick = onBack) { Text("Back to saved vehicles") }
        Text(data.text("vehicle_make"), style = MaterialTheme.typography.labelLarge)
        Text(data.text("chassis_number"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("${voyage.vessel} · ${voyage.voyage}")
        Text(if (record?.state == "synced") "Uploaded · ${record.reportNumber}" else if (record?.error?.isNotBlank() == true) "Needs attention" else "Saved on device", color = MaterialTheme.colorScheme.secondary)
        OutlinedCard { Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for ((key, label) in mapOf("deck" to "Deck", "key_count" to "Keys", "sensor_count" to "Sensors", "vip_key_number" to "VIP key number")) {
                SummaryValue(label, data.text(key).ifBlank { "Not recorded" }.let { if (it == "0" && key.endsWith("count")) "None (0)" else it })
            }
        } }
        for ((title, fields) in listOf("Exterior & visibility" to observationFields.entries.take(6), "Interior & controls" to observationFields.entries.drop(6))) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            fields.forEach { (key, label) ->
                SummaryValue(label, if (data.isNull(key)) "Not recorded" else if (data.optBoolean(key)) "Yes · Present" else "No · Not observed")
                HorizontalDivider()
            }
        }
        Text("Remarks", fontWeight = FontWeight.Bold)
        Text(data.text("remarks").ifBlank { "No remarks recorded." })
        record?.let { Text("Captured ${com.ped.mobile.displayTime(it.createdAt)}", style = MaterialTheme.typography.bodySmall) }
        if (record?.editable == true) OutlinedButton(onClick = onEdit, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Edit saved vehicle") }
    }
}
@Composable private fun SummaryValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), fontWeight = FontWeight.Medium)
    }
}
