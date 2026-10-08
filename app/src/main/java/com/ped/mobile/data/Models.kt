package com.ped.mobile.data

enum class AppScreen {
    LOGIN,
    CAPTURE,
    SYNC,
    INSPECTION,
    ACCOUNT,
    APP_UPDATES
}

enum class CaptureKind {
    PED,
    OFFDOCK,
    CONTAINER,
    SCRAP
}

data class TextFieldSpec(
    val key: String,
    val label: String,
    val required: Boolean = false,
    val multiline: Boolean = false,
    val placeholder: String = ""
)

data class ImageFieldSpec(
    val key: String,
    val label: String,
    val required: Boolean = false
)

data class CaptureSchema(
    val label: String,
    val endpoint: String,
    val titleKey: String,
    val description: String,
    val textFields: List<TextFieldSpec>,
    val imageFields: List<ImageFieldSpec>
)

data class PendingCapture(
    val id: String,
    val kind: CaptureKind,
    val endpoint: String,
    val title: String,
    val createdAt: String,
    val payload: Map<String, String>,
    val error: String = ""
)

data class UploadReceipt(val id: String, val title: String, val confirmedAt: String, val reference: String)
