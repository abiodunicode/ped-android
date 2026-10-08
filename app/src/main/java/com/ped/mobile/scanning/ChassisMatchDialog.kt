package com.ped.mobile.scanning

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import com.ped.mobile.ui.PedChassisTextStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

internal data class CargoCheck(val exact: Boolean = false, val suggestions: List<String> = emptyList(), val available: Boolean = true)

@Composable internal fun ChassisMatchDialog(
    number: String, original: String, alternatives: List<String>, enabled: Boolean,
    onChange: (String) -> Unit, onConfirm: (String) -> Unit, onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val normalized = number.trim().uppercase(Locale.ROOT)
    val valid = normalized.isNotEmpty() && normalized.length <= 100 && normalized.all { it in 'A'..'Z' || it in '0'..'9' }
    val check by produceState<CargoCheck?>(null, normalized) {
        value = null
        delay(180)
        value = withContext(Dispatchers.IO) {
            runCatching {
                val reference = CargoReference.get(context)
                val exact = normalized in reference.exact(listOf(normalized))
                CargoCheck(exact, if (exact) emptyList() else reference.similar(normalized))
            }.getOrElse { CargoCheck(available = false) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Check chassis number") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Read from photo: " + original, style = PedChassisTextStyle)
                Text("Compare with the vehicle label. Reference matches help you choose; they do not confirm the vehicle's identity.")
                OutlinedTextField(
                    number, onChange, label = { Text("Chassis number to use") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
                    textStyle = PedChassisTextStyle,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    isError = !valid,
                    supportingText = if (!valid) { { Text("Enter letters and numbers only (up to 100 characters).") } } else null
                )
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.fillMaxWidth().padding(12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(when {
                            check == null -> "Checking offline reference…"
                            check?.available == false -> "Reference unavailable"
                            check?.exact == true -> "Exact match in Cargo ID reference"
                            !check?.suggestions.isNullOrEmpty() -> "Possible match · check the label"
                            else -> "Not in reference"
                        }, style = MaterialTheme.typography.titleSmall)
                        Text(if (check?.exact == true) "Still check every character before confirming."
                            else "You can keep a new or unlisted chassis number.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                check?.suggestions.orEmpty().forEach { suggestion ->
                    OutlinedButton(onClick = { onChange(suggestion) }, shape = MaterialTheme.shapes.medium, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Compare " + suggestion, style = PedChassisTextStyle)
                            Text(suggestion.indices.filter { it < normalized.length && suggestion[it] != normalized[it] }.joinToString("; ") {
                                "Position " + (it + 1) + ": " + normalized[it] + " → " + suggestion[it]
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (alternatives.isNotEmpty()) {
                    Text("Other reference numbers found in the photo", style = MaterialTheme.typography.titleSmall)
                    alternatives.filter { it != normalized }.forEach { other ->
                        OutlinedButton(onClick = { onChange(other) }, shape = MaterialTheme.shapes.medium, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(other) }
                    }
                }
                if (normalized != original) TextButton(onClick = { onChange(original) }, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Restore scanned number") }
            }
        },
        confirmButton = {
            TextButton(enabled = enabled && valid, onClick = { onConfirm(normalized) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use this number") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Discard scan") }
        }
    )
}
