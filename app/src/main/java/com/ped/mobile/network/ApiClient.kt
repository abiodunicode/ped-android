package com.ped.mobile.network

import android.util.Base64
import com.ped.mobile.data.PendingCapture
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

class ApiClient(private val baseUrl: String) {
    fun latestAppRelease(): JSONObject? {
        val response = request("/app-release", "GET", null, null).getOrElse {
            throw IllegalStateException("Could not check for updates. Check your connection and try again.", it)
        }
        if (response.status !in 200..299) throw IllegalStateException(
            if (response.status == 404) "App updates are not available on this server yet. Contact your administrator."
            else "Could not check for updates (HTTP ${response.status}). Try again."
        )
        val json = response.bodyJson() ?: error("The server returned an invalid update response.")
        check(json.optBoolean("success", false) && json.has("data")) { "The server returned an invalid update response." }
        return if (json.isNull("data")) null else json.getJSONObject("data")
    }

    fun login(email: String, password: String): Result<String> {
        val payload = JSONObject()
            .put("email", email)
            .put("password", password)

        val response = request("/login", "POST", payload.toString(), null)
        return response.fold(
            onSuccess = { result ->
                if (result.status !in 200..299) {
                    Result.failure(IllegalStateException(result.errorMessage("Login failed.")))
                } else {
                    val token = result.bodyJson()
                        ?.optJSONObject("data")
                        ?.optString("token")
                        ?.takeIf { it.isNotBlank() }

                    if (token == null) {
                        Result.failure(IllegalStateException(result.errorMessage("Login token was not returned.")))
                    } else {
                        Result.success(token)
                    }
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    fun logout(token: String): Result<Unit> {
        val response = request("/logout", "POST", null, token)
        return response.fold(
            onSuccess = { result ->
                if (result.status !in 200..299) {
                    Result.failure(IllegalStateException(result.errorMessage("Logout failed.")))
                } else {
                    Result.success(Unit)
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    fun syncCapture(token: String, item: PendingCapture): Result<String> {
        val payload = runCatching {
            JSONObject().apply {
                item.payload.forEach { (key, value) ->
                    put(key, resolvePayloadValue(value))
                }
            }
        }.getOrElse { error ->
            return Result.failure(
                IllegalStateException(
                    "Unable to prepare queued capture for sync. ${error.message ?: "Check local image files."}",
                    error
                )
            )
        }

        val response = request(item.endpoint, "POST", payload.toString(), token)
        return response.fold(
            onSuccess = { result ->
                if (result.status !in 200..299) {
                    Result.failure(InspectionApiException(result.status, result.errorMessage("Upload failed.")))
                } else if (result.bodyJson() == null || result.bodyJson()?.optBoolean("success", true) == false) {
                    Result.failure(InspectionApiException(result.status, result.errorMessage("Upload failed.")))
                } else {
                    val data = result.bodyJson()?.optJSONObject("data")
                    val id = data?.optLong("id", 0) ?: 0
                    val duplicate = data?.optLong("duplicate_case_id", 0) ?: 0
                    when {
                        id > 0 -> Result.success(id.toString())
                        duplicate > 0 && data?.optString("status") == "pending_review" -> Result.success("Review case $duplicate")
                        else -> Result.failure(InspectionApiException(result.status, "The server did not confirm a record reference. Saved work is kept."))
                    }
                }
            },
            onFailure = { Result.failure(it) }
        )
    }

    fun inspectionRequest(token: String, path: String, method: String = "GET", payload: JSONObject? = null): JSONObject {
        val response = request(path, method, payload?.toString(), token).getOrElse {
            if (com.ped.mobile.BuildConfig.DEBUG) android.util.Log.w("InspectionApi", "Inspection connection failed", it)
            throw IllegalStateException("Could not connect. Your saved vehicles remain on this device. Reconnect and retry.", it)
        }
        val json = response.bodyJson()
        if (response.status !in 200..299 || json?.optBoolean("success", true) == false) {
            val errors = json?.optJSONObject("errors")
            val detail = errors?.keys()?.asSequence()?.map { key ->
                "$key: ${errors.optJSONArray(key)?.optString(0).orEmpty()}"
            }?.joinToString("\n").orEmpty()
            throw InspectionApiException(response.status,
                if (response.status == 401) "Session expired. Sign in again with the same account. Your saved vehicles are safe."
                else listOf(response.errorMessage("Request failed."), detail).filter { it.isNotBlank() }.joinToString("\n"))
        }
        return json?.getJSONObject("data") ?: error("The API returned an invalid response. Nothing was removed from this device.")
    }

    private fun request(path: String, method: String, body: String?, token: String?): Result<ResponseData> {
        return runCatching {
            val connection = (URL(composeUrl(path)).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                doInput = true
                setRequestProperty("Accept", "application/json")
                if (path == "/app-release") { useCaches = false; setRequestProperty("Cache-Control", "no-cache") }
                setRequestProperty("ngrok-skip-browser-warning", "true")

                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer $token")
                }

                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }

            body?.let {
                OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { writer ->
                    writer.write(it)
                    writer.flush()
                }
            }

            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val responseBody = readStream(stream)
            connection.disconnect()

            ResponseData(status, responseBody)
        }.recoverCatching { error ->
            throw IllegalStateException(
                "Failed to reach API. Check your HTTPS API URL, tunnel, and network connection.",
                error
            )
        }
    }

    private fun composeUrl(path: String): String {
        val normalizedBase = baseUrl.trimEnd('/')
        val normalizedPath = if (path.startsWith("/")) path else "/$path"
        return normalizedBase + normalizedPath
    }

    private fun resolvePayloadValue(value: String): String {
        if (!value.startsWith(FILE_REF_PREFIX)) {
            return value
        }

        val imagePath = value.removePrefix(FILE_REF_PREFIX)
        if (imagePath.isBlank()) {
            throw IllegalStateException("Queued image reference is invalid.")
        }

        val imageFile = File(imagePath)
        if (!imageFile.exists()) {
            throw IllegalStateException("Queued image file is missing (${imageFile.name}).")
        }

        val bytes = imageFile.readBytes()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val mimeType = mimeTypeFor(imageFile)
        return "data:$mimeType;base64,$encoded"
    }

    private fun mimeTypeFor(file: File): String {
        return when (file.extension.lowercase()) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic", "heif" -> "image/heic"
            else -> "image/jpeg"
        }
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) {
            return ""
        }

        return BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
            buildString {
                while (true) {
                    val line = reader.readLine() ?: break
                    append(line)
                }
            }
        }
    }

    private data class ResponseData(
        val status: Int,
        val body: String
    ) {
        fun bodyJson(): JSONObject? = runCatching { JSONObject(body) }.getOrNull()

        fun errorMessage(fallback: String): String {
            val json = bodyJson()
            val message = json?.optString("message").orEmpty().trim()
            val detail = json?.optString("error").orEmpty().trim()
            val responseBody = body.trim()

            return when {
                message.isNotEmpty() && detail.isNotEmpty() -> "$message: $detail"
                message.isNotEmpty() -> message
                detail.isNotEmpty() -> detail
                responseBody.startsWith("<!DOCTYPE", ignoreCase = true) || responseBody.startsWith("<html", ignoreCase = true) ->
                    "Ngrok returned HTML instead of API JSON. Verify the tunnel routes /api to the Go backend."
                status > 0 -> "$fallback (HTTP $status)"
                else -> fallback
            }
        }
    }

    private companion object {
        const val FILE_REF_PREFIX = "pedfile:"
    }
}

class InspectionApiException(val status: Int, message: String) : IllegalStateException(message)
