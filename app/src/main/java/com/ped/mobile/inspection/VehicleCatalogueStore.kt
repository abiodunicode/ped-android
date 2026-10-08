package com.ped.mobile.inspection

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.time.Instant

/** Reference data is isolated by API/account, independently of unsent captures. */
class VehicleCatalogueStore(context: Context) : SQLiteOpenHelper(context, "vehicle-catalogue.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE models (owner TEXT NOT NULL, id INTEGER NOT NULL, make TEXT NOT NULL, model TEXT NOT NULL, active INTEGER NOT NULL, revision INTEGER NOT NULL, PRIMARY KEY(owner,id))")
        db.execSQL("CREATE INDEX model_lookup ON models(owner,active,make,model)")
        db.execSQL("CREATE TABLE sync_state (owner TEXT PRIMARY KEY, catalogue_id TEXT NOT NULL, cursor INTEGER NOT NULL, updated_at TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun state(owner: String): Pair<String, Long> = readableDatabase.rawQuery("SELECT catalogue_id,cursor FROM sync_state WHERE owner=?", arrayOf(owner)).use {
        if (it.moveToFirst()) it.getString(0) to it.getLong(1) else "" to 0L
    }
    @Synchronized fun count(owner: String): Int = readableDatabase.rawQuery("SELECT COUNT(*) FROM models WHERE owner=? AND active=1", arrayOf(owner)).use { it.moveToFirst(); it.getInt(0) }
    @Synchronized fun search(owner: String, query: String): List<String> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.take(8)
        if (words.isEmpty()) return emptyList()
        val conditions = words.joinToString(" AND ") { "(make LIKE ? ESCAPE '!' OR model LIKE ? ESCAPE '!')" }
        val args = mutableListOf(owner)
        words.forEach { word -> val escaped = "%" + word.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"; args += escaped; args += escaped }
        return readableDatabase.rawQuery("SELECT make,model FROM models WHERE owner=? AND active=1 AND $conditions ORDER BY make,model LIMIT 6", args.toTypedArray()).use { cursor ->
            buildList { while (cursor.moveToNext()) add("${cursor.getString(0)} ${cursor.getString(1)}") }
        }
    }
    /** A page and its cursor commit together. Interrupted downloads resume safely. */
    @Synchronized fun apply(owner: String, page: JSONObject, requestedCursor: Long) {
        val identity = page.getString("catalogue_id")
        val next = page.getLong("next_cursor")
        require(next >= requestedCursor) { "Invalid catalogue cursor." }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = state(owner)
            if (current.first != identity) {
                require(requestedCursor == 0L) { "Catalogue changed; restart the download." }
                db.delete("models", "owner=?", arrayOf(owner))
            } else require(current.second == requestedCursor) { "A newer catalogue refresh is already saved." }
            var previous = requestedCursor
            page.getJSONArray("data").objects().forEach { row ->
                val revision = row.getLong("revision")
                require(revision > previous && revision <= next) { "Invalid catalogue order." }; previous = revision
                require(row.getLong("id") > 0 && row.getString("make").isNotBlank() && row.getString("model").isNotBlank())
                db.insertOrThrow("models", null, ContentValues().apply {
                    put("owner", owner); put("id", row.getLong("id")); put("make", row.getString("make")); put("model", row.getString("model")); put("active", if (row.getBoolean("active")) 1 else 0); put("revision", revision)
                }.also { db.delete("models", "owner=? AND id=?", arrayOf(owner, row.getLong("id").toString())) })
            }
            require(previous == next) { "Catalogue cursor does not match its rows." }
            db.insertWithOnConflict("sync_state", null, ContentValues().apply { put("owner", owner); put("catalogue_id", identity); put("cursor", next); put("updated_at", Instant.now().toString()) }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
}
