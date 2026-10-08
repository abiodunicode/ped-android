package com.ped.mobile.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Base64
import android.util.JsonReader
import android.util.JsonToken
import org.json.JSONObject
import java.io.File
import java.io.StringReader
import java.util.UUID

class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("ped-session", Context.MODE_PRIVATE)

    fun readToken(): String? = prefs.getString(KEY_TOKEN, null)

    fun readRememberMe(): Boolean = prefs.getBoolean(KEY_REMEMBER_ME, false)

    fun writeToken(token: String) {
        prefs.edit().putString(KEY_TOKEN, token).apply()
    }

    fun writeRememberMe(value: Boolean) {
        prefs.edit().putBoolean(KEY_REMEMBER_ME, value).apply()
    }

    fun clearToken() {
        prefs.edit().remove(KEY_TOKEN).apply()
    }

    private companion object {
        const val KEY_TOKEN = "auth_token"
        const val KEY_REMEMBER_ME = "remember_me"
    }
}

class QueueStore(context: Context, owner: String? = null) {
    private val namespace = owner?.let { java.security.MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }
    private val metadata = context.getSharedPreferences("capture-status-${namespace ?: "legacy"}", Context.MODE_PRIVATE)
    private val draftFile = android.util.AtomicFile(File(context.filesDir, "capture-draft-${namespace ?: "legacy"}.json"))
    private val scoped = owner != null
    private val draftLock = draftLocks.getOrPut(namespace ?: "legacy") { Any() }
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)
    private val dbHelper = QueueDbHelper(appContext, namespace?.let { "ped_queue_$it.db" } ?: DB_NAME)
    private val queueImageDir = File(appContext.filesDir, QUEUE_IMAGE_DIR)
    private val managedPrefix by lazy {
        queueImageDir.canonicalFile.absolutePath + File.separator
    }

    init {
        queueImageDir.mkdirs()
        if (!scoped) {
            migrateLegacyQueueIfNeeded()
            migrateDbRowsToFileRefsIfNeeded()
        }
    }

    @Synchronized
    fun loadQueue(): List<PendingCapture> {
        val db = dbHelper.readableDatabase
        val captures = mutableListOf<PendingCapture>()
        db.query(
            QueueDbHelper.TABLE_CAPTURES,
            arrayOf(
                QueueDbHelper.COL_ID,
                QueueDbHelper.COL_KIND,
                QueueDbHelper.COL_ENDPOINT,
                QueueDbHelper.COL_TITLE,
                QueueDbHelper.COL_CREATED_AT
            ),
            null,
            null,
            null,
            null,
            "${QueueDbHelper.COL_SAVED_AT} DESC"
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(QueueDbHelper.COL_ID)
            val kindIndex = cursor.getColumnIndexOrThrow(QueueDbHelper.COL_KIND)
            val endpointIndex = cursor.getColumnIndexOrThrow(QueueDbHelper.COL_ENDPOINT)
            val titleIndex = cursor.getColumnIndexOrThrow(QueueDbHelper.COL_TITLE)
            val createdAtIndex = cursor.getColumnIndexOrThrow(QueueDbHelper.COL_CREATED_AT)
            while (cursor.moveToNext()) {
                val kind = runCatching {
                    CaptureKind.valueOf(cursor.getString(kindIndex))
                }.getOrNull() ?: continue

                captures += PendingCapture(
                    id = cursor.getString(idIndex),
                    kind = kind,
                    endpoint = cursor.getString(endpointIndex),
                    title = cursor.getString(titleIndex),
                    createdAt = cursor.getString(createdAtIndex),
                    payload = emptyMap(),
                    error = metadata.getString("error:${cursor.getString(idIndex)}", "").orEmpty()
                )
            }
        }
        // A receipt is persisted before removing a queue row, so an interrupted cleanup cannot resend it.
        return captures.filterNot { metadata.contains("uploaded:${it.id}") }
    }

    @Synchronized
    fun loadCaptureForSync(id: String): PendingCapture? {
        if (metadata.contains("uploaded:$id")) return null
        val db = dbHelper.readableDatabase
        var metadata: PendingCapture? = null
        db.query(
            QueueDbHelper.TABLE_CAPTURES,
            arrayOf(
                QueueDbHelper.COL_ID,
                QueueDbHelper.COL_KIND,
                QueueDbHelper.COL_ENDPOINT,
                QueueDbHelper.COL_TITLE,
                QueueDbHelper.COL_CREATED_AT
            ),
            "${QueueDbHelper.COL_ID} = ?",
            arrayOf(id),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            if (!cursor.moveToFirst()) {
                return null
            }
            val parsedKind = runCatching {
                CaptureKind.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(QueueDbHelper.COL_KIND)))
            }.getOrNull() ?: return null

            metadata = PendingCapture(
                id = cursor.getString(cursor.getColumnIndexOrThrow(QueueDbHelper.COL_ID)),
                kind = parsedKind,
                endpoint = cursor.getString(cursor.getColumnIndexOrThrow(QueueDbHelper.COL_ENDPOINT)),
                title = cursor.getString(cursor.getColumnIndexOrThrow(QueueDbHelper.COL_TITLE)),
                createdAt = cursor.getString(cursor.getColumnIndexOrThrow(QueueDbHelper.COL_CREATED_AT)),
                payload = emptyMap()
            )
        }

        val payload = parsePayload(queryPayloadJsonById(db, id))
        val item = metadata ?: return null
        return item.copy(payload = payload)
    }

    @Synchronized
    fun saveQueue(items: List<PendingCapture>) {
        val db = dbHelper.writableDatabase
        val oldRefs = collectAllManagedFileRefs(db)
        val normalizedItems = items.map { item -> item.copy(payload = toStoredPayload(item.payload)) }
        val newRefs = normalizedItems.flatMapTo(mutableSetOf()) { item ->
            extractManagedFilePaths(item.payload)
        }

        db.beginTransaction()
        try {
            db.delete(QueueDbHelper.TABLE_CAPTURES, null, null)
            normalizedItems.forEach { item ->
                insertCapture(db, item)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        (oldRefs - newRefs).forEach(::deleteManagedFilePath)
    }

    @Synchronized
    fun add(item: PendingCapture): List<PendingCapture> {
        val db = dbHelper.writableDatabase
        insertCapture(db, item)
        return loadQueue()
    }

    @Synchronized
    fun remove(id: String): List<PendingCapture> {
        val db = dbHelper.writableDatabase
        val payloadRaw = queryPayloadJsonById(db, id)
        db.delete(QueueDbHelper.TABLE_CAPTURES, "${QueueDbHelper.COL_ID} = ?", arrayOf(id))
        deleteManagedFilesFromPayload(payloadRaw)
        return loadQueue()
    }

    @Synchronized
    fun clear(): List<PendingCapture> {
        val db = dbHelper.writableDatabase
        val payloadRows = queryAllPayloadRows(db)
        db.delete(QueueDbHelper.TABLE_CAPTURES, null, null)
        payloadRows.forEach(::deleteManagedFilesFromPayload)
        return emptyList()
    }

    @Synchronized fun failed(id: String, message: String) {
        check(metadata.edit().putString("error:$id", message).commit()) { "Could not retain upload error." }
    }
    @Synchronized fun uploaded(item: PendingCapture, reference: String) {
        val record = JSONObject().put("id", item.id).put("title", item.title).put("kind", item.kind.name)
            .put("confirmed", java.time.Instant.now().toString()).put("reference", reference)
        check(metadata.edit().putString("uploaded:${item.id}", record.toString()).remove("error:${item.id}").commit()) { "Could not save upload receipt." }
        remove(item.id)
    }
    @Synchronized fun history(): List<UploadReceipt> = metadata.all.filterKeys { it.startsWith("uploaded:") }.values.mapNotNull {
        runCatching { val v = JSONObject(it as String); UploadReceipt(v.getString("id"), v.getString("title"), v.getString("confirmed"), v.getString("reference")) }.getOrNull()
    }.sortedByDescending { it.confirmedAt }
    fun saveDraft(kind: CaptureKind, payload: Map<String, String>) = synchronized(draftLock) {
        val json = JSONObject().put("kind", kind.name).put("payload", JSONObject(payload))
        val stream = draftFile.startWrite()
        try { stream.write(json.toString().toByteArray()); draftFile.finishWrite(stream) }
        catch (error: Exception) { draftFile.failWrite(stream); throw error }
    }
    fun readDraft(): Pair<CaptureKind, Map<String, String>>? = synchronized(draftLock) { runCatching {
        val json = JSONObject(String(draftFile.readFully()))
        CaptureKind.valueOf(json.getString("kind")) to parsePayload(json.getJSONObject("payload").toString())
    }.getOrNull() }
    fun clearDraft() = synchronized(draftLock) { draftFile.delete() }

    fun close() = dbHelper.close()

    fun newCaptureId(): String = UUID.randomUUID().toString()

    private fun insertCapture(db: SQLiteDatabase, item: PendingCapture, savedAtOverride: Long? = null) {
        val storedPayload = toStoredPayload(item.payload)
        db.insertWithOnConflict(
            QueueDbHelper.TABLE_CAPTURES,
            null,
            ContentValues().apply {
                put(QueueDbHelper.COL_ID, item.id)
                put(QueueDbHelper.COL_KIND, item.kind.name)
                put(QueueDbHelper.COL_ENDPOINT, item.endpoint)
                put(QueueDbHelper.COL_TITLE, item.title)
                put(QueueDbHelper.COL_CREATED_AT, item.createdAt)
                put(QueueDbHelper.COL_PAYLOAD_JSON, payloadToJson(storedPayload))
                put(QueueDbHelper.COL_SAVED_AT, savedAtOverride ?: System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun toStoredPayload(payload: Map<String, String>): Map<String, String> {
        val stored = linkedMapOf<String, String>()
        payload.forEach { (key, value) ->
            stored[key] = when {
                value.startsWith(FILE_REF_PREFIX) -> value
                isImageDataUrl(value) -> persistDataUrlAsFileRef(value)
                else -> value
            }
        }
        return stored
    }

    private fun persistDataUrlAsFileRef(dataUrl: String): String {
        val commaIndex = dataUrl.indexOf(',')
        if (commaIndex <= 0) {
            return dataUrl
        }

        val metadata = dataUrl.substring(5, commaIndex)
        if (!metadata.contains(";base64", ignoreCase = true)) {
            return dataUrl
        }

        val mimeType = metadata.substringBefore(';').ifBlank { "image/jpeg" }
        val base64Payload = dataUrl.substring(commaIndex + 1)
        if (base64Payload.isBlank()) {
            return dataUrl
        }

        val bytes = runCatching { Base64.decode(base64Payload, Base64.DEFAULT) }.getOrNull() ?: return dataUrl
        val extension = extensionForMime(mimeType)
        val imageFile = File(queueImageDir, "${UUID.randomUUID()}.$extension")

        val writeResult = runCatching {
            imageFile.outputStream().use { output ->
                output.write(bytes)
            }
        }
        if (writeResult.isFailure) {
            imageFile.delete()
            return dataUrl
        }

        return FILE_REF_PREFIX + imageFile.absolutePath
    }

    private fun isImageDataUrl(value: String): Boolean {
        return value.startsWith("data:image/", ignoreCase = true) && value.contains(";base64,", ignoreCase = true)
    }

    private fun extensionForMime(mimeType: String): String {
        return when (mimeType.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/heic", "image/heif" -> "heic"
            else -> "jpg"
        }
    }

    private fun payloadToJson(payload: Map<String, String>): String {
        val payloadObject = JSONObject()
        payload.forEach { (key, value) ->
            payloadObject.put(key, value)
        }
        return payloadObject.toString()
    }

    private fun parsePayload(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }
        return runCatching {
            val payloadObject = JSONObject(raw)
            val payload = linkedMapOf<String, String>()
            val keys = payloadObject.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                payload[key] = payloadObject.optString(key)
            }
            payload
        }.getOrDefault(emptyMap())
    }

    private fun queryPayloadJsonById(db: SQLiteDatabase, id: String): String? {
        return runCatching {
            val statement = db.compileStatement(
                "SELECT ${QueueDbHelper.COL_PAYLOAD_JSON} FROM ${QueueDbHelper.TABLE_CAPTURES} WHERE ${QueueDbHelper.COL_ID} = ? LIMIT 1"
            )
            try {
                statement.bindString(1, id)
                statement.simpleQueryForString()
            } finally {
                statement.close()
            }
        }.getOrNull()
    }

    private fun queryAllPayloadRows(db: SQLiteDatabase): List<String> {
        val ids = mutableListOf<String>()
        db.query(
            QueueDbHelper.TABLE_CAPTURES,
            arrayOf(QueueDbHelper.COL_ID),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(QueueDbHelper.COL_ID)
            while (cursor.moveToNext()) {
                ids += cursor.getString(idIndex)
            }
        }

        return ids.mapNotNull { id ->
            queryPayloadJsonById(db, id)
        }
    }

    private fun extractManagedFilePaths(payload: Map<String, String>): Set<String> {
        return payload.values.mapNotNullTo(mutableSetOf()) { value ->
            managedFileFromRef(value)?.absolutePath
        }
    }

    private fun collectAllManagedFileRefs(db: SQLiteDatabase): Set<String> {
        val refs = mutableSetOf<String>()
        queryAllPayloadRows(db).forEach { raw ->
            val payload = parsePayload(raw)
            refs += extractManagedFilePaths(payload)
        }
        return refs
    }

    private fun deleteManagedFilesFromPayload(rawPayload: String?) {
        val payload = parsePayload(rawPayload)
        payload.values.forEach { value ->
            val file = managedFileFromRef(value) ?: return@forEach
            runCatching {
                if (file.exists()) {
                    file.delete()
                }
            }
        }
    }

    private fun deleteManagedFilePath(path: String) {
        val file = managedFileFromRef(FILE_REF_PREFIX + path) ?: return
        runCatching {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun managedFileFromRef(value: String): File? {
        if (!value.startsWith(FILE_REF_PREFIX)) {
            return null
        }

        val rawPath = value.removePrefix(FILE_REF_PREFIX)
        if (rawPath.isBlank()) {
            return null
        }

        val canonical = runCatching { File(rawPath).canonicalFile }.getOrNull() ?: return null
        val canonicalPath = canonical.absolutePath
        return if (canonicalPath.startsWith(managedPrefix)) canonical else null
    }

    private fun migrateLegacyQueueIfNeeded() {
        if (prefs.getBoolean(KEY_SQLITE_MIGRATED, false)) {
            return
        }

        val raw = prefs.getString(KEY_QUEUE, null)
        if (raw.isNullOrBlank()) {
            prefs.edit().putBoolean(KEY_SQLITE_MIGRATED, true).apply()
            return
        }

        val migrationResult = runCatching {
            val db = dbHelper.writableDatabase
            val baseSavedAt = System.currentTimeMillis()
            var index = 0

            db.beginTransaction()
            try {
                JsonReader(StringReader(raw)).use { reader ->
                    reader.beginArray()
                    while (reader.hasNext()) {
                        val capture = readLegacyCapture(reader) ?: continue
                        insertCapture(
                            db = db,
                            item = capture,
                            savedAtOverride = baseSavedAt - index
                        )
                        index += 1
                    }
                    reader.endArray()
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        if (migrationResult.isFailure) {
            return
        }

        prefs.edit()
            .remove(KEY_QUEUE)
            .putBoolean(KEY_SQLITE_MIGRATED, true)
            .apply()
    }

    private fun readLegacyCapture(reader: JsonReader): PendingCapture? {
        var id: String? = null
        var kindRaw: String? = null
        var endpoint: String? = null
        var title: String? = null
        var createdAt: String? = null
        var payload: Map<String, String> = emptyMap()

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "id" -> id = readString(reader)
                "kind" -> kindRaw = readString(reader)
                "endpoint" -> endpoint = readString(reader)
                "title" -> title = readString(reader)
                "createdAt" -> createdAt = readString(reader)
                "payload" -> payload = readPayloadObject(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        val captureId = id?.takeIf { it.isNotBlank() } ?: return null
        val parsedKind = runCatching { CaptureKind.valueOf(kindRaw.orEmpty()) }.getOrNull() ?: return null
        val captureEndpoint = endpoint?.takeIf { it.isNotBlank() } ?: return null
        val captureTitle = title?.takeIf { it.isNotBlank() } ?: return null
        val captureCreatedAt = createdAt?.takeIf { it.isNotBlank() } ?: return null

        return PendingCapture(
            id = captureId,
            kind = parsedKind,
            endpoint = captureEndpoint,
            title = captureTitle,
            createdAt = captureCreatedAt,
            payload = payload
        )
    }

    private fun readPayloadObject(reader: JsonReader): Map<String, String> {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return emptyMap()
        }

        val payload = linkedMapOf<String, String>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            payload[key] = readStringValue(reader)
        }
        reader.endObject()
        return payload
    }

    private fun readString(reader: JsonReader): String? {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull()
            return null
        }
        return readStringValue(reader)
    }

    private fun readStringValue(reader: JsonReader): String {
        return when (reader.peek()) {
            JsonToken.STRING -> reader.nextString()
            JsonToken.NUMBER -> reader.nextString()
            JsonToken.BOOLEAN -> reader.nextBoolean().toString()
            JsonToken.NULL -> {
                reader.nextNull()
                ""
            }
            else -> {
                reader.skipValue()
                ""
            }
        }
    }

    private fun migrateDbRowsToFileRefsIfNeeded() {
        if (prefs.getBoolean(KEY_DB_FILE_REF_MIGRATED, false)) {
            return
        }

        val db = dbHelper.writableDatabase
        val ids = mutableListOf<String>()
        db.query(
            QueueDbHelper.TABLE_CAPTURES,
            arrayOf(QueueDbHelper.COL_ID),
            null,
            null,
            null,
            null,
            null
        ).use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(QueueDbHelper.COL_ID)
            while (cursor.moveToNext()) {
                ids += cursor.getString(idIndex)
            }
        }

        if (ids.isEmpty()) {
            prefs.edit().putBoolean(KEY_DB_FILE_REF_MIGRATED, true).apply()
            return
        }

        val migrationResult = runCatching {
            db.beginTransaction()
            try {
                ids.forEach { id ->
                    val payloadRaw = queryPayloadJsonById(db, id) ?: return@forEach
                    val payload = parsePayload(payloadRaw)
                    val storedPayload = toStoredPayload(payload)
                    if (storedPayload != payload) {
                        db.update(
                            QueueDbHelper.TABLE_CAPTURES,
                            ContentValues().apply {
                                put(QueueDbHelper.COL_PAYLOAD_JSON, payloadToJson(storedPayload))
                            },
                            "${QueueDbHelper.COL_ID} = ?",
                            arrayOf(id)
                        )
                    }
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        if (migrationResult.isSuccess) {
            prefs.edit().putBoolean(KEY_DB_FILE_REF_MIGRATED, true).apply()
        }
    }

    private class QueueDbHelper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_CAPTURES (
                    $COL_ID TEXT PRIMARY KEY,
                    $COL_KIND TEXT NOT NULL,
                    $COL_ENDPOINT TEXT NOT NULL,
                    $COL_TITLE TEXT NOT NULL,
                    $COL_CREATED_AT TEXT NOT NULL,
                    $COL_PAYLOAD_JSON TEXT NOT NULL,
                    $COL_SAVED_AT INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE INDEX IF NOT EXISTS idx_${TABLE_CAPTURES}_saved_at
                ON $TABLE_CAPTURES ($COL_SAVED_AT DESC)
                """.trimIndent()
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_CAPTURES")
            onCreate(db)
        }

        companion object {
            const val TABLE_CAPTURES = "pending_captures"
            const val COL_ID = "id"
            const val COL_KIND = "kind"
            const val COL_ENDPOINT = "endpoint"
            const val COL_TITLE = "title"
            const val COL_CREATED_AT = "created_at"
            const val COL_PAYLOAD_JSON = "payload_json"
            const val COL_SAVED_AT = "saved_at"
        }
    }

    private companion object {
        val draftLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        const val DB_NAME = "ped_queue.db"
        const val DB_VERSION = 1
        const val LEGACY_PREF_NAME = "ped-queue"
        const val KEY_QUEUE = "pending_queue"
        const val KEY_SQLITE_MIGRATED = "sqlite_migrated"
        const val KEY_DB_FILE_REF_MIGRATED = "db_file_ref_migrated"
        const val QUEUE_IMAGE_DIR = "queue-images"
        const val FILE_REF_PREFIX = "pedfile:"
    }
}
