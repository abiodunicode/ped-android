package com.ped.mobile.inspection

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

val observationFields = linkedMapOf(
    "side_mirror" to "Side mirror", "rear_mirror" to "Rear mirror",
    "front_camera" to "Front camera", "rear_camera" to "Rear camera",
    "front_logo" to "Front logo", "rear_logo" to "Rear logo",
    "radio" to "Radio", "window_controls" to "Window controls",
    "ac_knob" to "AC knob", "map_knob" to "Map knob", "gear_knob" to "Gear knob"
)

data class InspectionAccount(val id: Long, val email: String, val permissions: Set<String>? = null, val name: String = "", val accountType: String = "") {
    // A verified permission snapshot is required, including when reopening offline.
    fun can(permission: String): Boolean = permissions?.contains(permission) ?: false
}
data class Voyage(val id: Long, val vessel: String, val voyage: String, val completed: Boolean) {
    fun json() = JSONObject().put("id", id).put("vessel", vessel).put("voyage", voyage).put("completed", completed)
    companion object {
        fun read(json: JSONObject) = Voyage(json.getLong("id"), json.getString("vessel"), json.getString("voyage"),
            json.optBoolean("completed") || json.optJSONObject("report")?.optString("status") == "completed")
    }
}
data class InspectionDirectory(val voyages: List<Voyage>, val makes: List<String>, val updatedAt: String)
data class LocalTally(val id: String, val voyage: Voyage, val payload: String, val state: String,
    val createdAt: String, val error: String, val reportNumber: String, val syncedAt: String = "", val errorStatus: Int = 0) {
    val data: JSONObject get() = JSONObject(payload)
    val editable: Boolean get() = state == "pending"
    val recoveryAction: String get() = when (errorStatus) {
        401 -> "Sign in again with this account"
        403, 409, 422 -> "Administrator review required"
        else -> "Reconnect and retry upload"
    }
}
fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key)

fun validationErrors(form: JSONObject): Map<String, String> = buildMap {
    for ((key, limit) in mapOf("vehicle_make" to 120, "chassis_number" to 100, "deck" to 40, "vip_key_number" to 100, "remarks" to 4000)) {
        val value = form.text(key).trim()
        if (key in listOf("vehicle_make", "chassis_number") && value.isEmpty()) put(key, "This field is required.")
        else if (value.length > limit) put(key, "Use $limit characters or fewer.")
    }
    val chassis = form.text("chassis_number").trim()
    if (chassis.isNotEmpty() && !Regex("^[A-Za-z0-9]+$").matches(chassis)) put("chassis_number", "Use letters and numbers only.")
    for (key in listOf("key_count", "sensor_count")) {
        val value = form.text(key)
        if (value.isNotBlank() && value.toIntOrNull() !in 0..3) put(key, "Choose 0, 1, 2 or 3.")
    }
}
fun vehiclePayload(form: JSONObject, submissionId: String): JSONObject {
    require(validationErrors(form).isEmpty()) { "Please correct the highlighted fields." }
    return JSONObject().apply {
        put("submission_id", submissionId)
        for (key in listOf("vehicle_make", "chassis_number", "deck", "vip_key_number", "remarks")) {
            var value = form.text(key).trim()
            if (key == "chassis_number") value = value.uppercase(Locale.ROOT)
            put(key, value.ifEmpty { null } ?: JSONObject.NULL)
        }
        for (key in listOf("key_count", "sensor_count")) put(key, form.text(key).toIntOrNull() ?: JSONObject.NULL)
        for (key in observationFields.keys) put(key, if (form.isNull(key)) JSONObject.NULL else form.getBoolean(key))
    }
}
