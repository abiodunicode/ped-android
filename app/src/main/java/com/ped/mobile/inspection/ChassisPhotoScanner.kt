package com.ped.mobile.inspection

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.ped.mobile.scanning.CargoReference
import com.ped.mobile.scanning.matchChassis
import com.ped.mobile.scanning.ChassisMatchDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Bundled on-device recognition: photos never leave the device. */
@Composable
internal fun ChassisPhotoScanner(enabled: Boolean, onUseNumber: (String) -> Unit, onBusyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val useNumber by rememberUpdatedState(onUseNumber)
    var pendingPath by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var candidate by rememberSaveable { mutableStateOf<String?>(null) }
    var scannedText by rememberSaveable { mutableStateOf<String?>(null) }
    var original by rememberSaveable { mutableStateOf("") }
    var alternatives by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var message by rememberSaveable { mutableStateOf("") }
    val recognizer = remember { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(recognizer) {
        onDispose { active = false; recognizer.close() }
    }
    LaunchedEffect(reading, pendingPath, candidate, scannedText) {
        onBusyChange(reading || pendingPath != null || candidate != null || scannedText != null)
    }
    LaunchedEffect(scannedText) {
        val text = scannedText ?: return@LaunchedEffect
        message = "Checking offline Cargo ID reference…"
        val match = withContext(Dispatchers.IO) {
            runCatching { matchChassis(text, CargoReference.get(context)) }.getOrElse { matchChassis(text, null) }
        }
        candidate = match?.number
        original = match?.number.orEmpty()
        alternatives = if (match?.exact == true) match.suggestions else emptyList()
        scannedText = null
        message = if (match == null) "No clear chassis number found. Retake the photo closer to the label, or type it manually."
            else "Review the extracted number before using it."
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = pendingPath?.let(::File)
        pendingPath = null
        if (!success || file == null) {
            file?.delete()
            message = "Scan cancelled. Your chassis number has not changed."
        } else {
            val input = runCatching { InputImage.fromFilePath(context, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)) }
            input.onFailure {
                file.delete()
                message = "Could not open the photo. Try again or type the chassis number."
            }.onSuccess { image ->
                reading = true
                message = "Reading chassis label…"
                recognizer.process(image).addOnSuccessListener { result ->
                    if (active) {
                        scannedText = result.text
                    }
                }.addOnFailureListener {
                    if (active) message = "Could not read the chassis label. Try another photo or type the number."
                }.addOnCompleteListener {
                    file.delete()
                    if (active) reading = false
                }
            }
        }
    }
    OutlinedButton(onClick = {
        runCatching {
            val directory = File(context.cacheDir, "camera").apply { mkdirs() }
            val file = File.createTempFile("ped_chassis_", ".jpg", directory)
            pendingPath = file.absolutePath
            camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        }.onFailure {
            pendingPath?.let { File(it).delete() }; pendingPath = null
            message = "Camera unavailable. You can still type the chassis number."
        }
    }, enabled = enabled && !reading && scannedText == null && candidate == null && pendingPath == null, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(if (reading || scannedText != null) "Reading chassis…" else "Scan chassis from photo")
    }
    Text("Take a clear photo of the chassis / VIN label. Works offline; check the number before saving.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (message.isNotBlank()) Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium)
    candidate?.let { number ->
        ChassisMatchDialog(
            number = number, original = original, alternatives = alternatives, enabled = enabled,
            onChange = { candidate = it },
            onConfirm = { confirmed ->
                useNumber(confirmed); candidate = null
                message = "Chassis number filled. Check it before saving the record."
            },
            onDismiss = {
                candidate = null
                message = "Scan discarded. Your chassis number has not changed."
            }
        )
    }
}
