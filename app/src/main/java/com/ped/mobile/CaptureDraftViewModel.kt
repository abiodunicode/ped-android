package com.ped.mobile

import androidx.compose.runtime.mutableStateOf
import com.ped.mobile.data.ImageFieldSpec
import androidx.compose.runtime.mutableStateMapOf
import androidx.lifecycle.ViewModel

/** Keep large photo drafts out of the saved-instance Bundle while surviving rotation. */
class CaptureDraftViewModel : ViewModel() {
    val captureBusy = mutableStateOf(false)
    val captureMessage = mutableStateOf("")
    val syncBusy = mutableStateOf(false)
    val syncMessage = mutableStateOf("")
    val queueRevision = mutableStateOf(0)
    val activeImageField = mutableStateOf<ImageFieldSpec?>(null)
    val pendingImagePath = mutableStateOf<String?>(null)
    val textValues = mutableStateMapOf<String, String>()
    val imageValues = mutableStateMapOf<String, String>()
}
