package com.ped.mobile.inspection

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Separate database: upgrades never touch the existing PED/image capture queue. */
class InspectionStore(context: Context) : SQLiteOpenHelper(context, "pre-inspection.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE directory (owner TEXT PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE tally (id TEXT PRIMARY KEY, owner TEXT NOT NULL, voyage TEXT NOT NULL, payload TEXT NOT NULL, state TEXT NOT NULL, created_at TEXT NOT NULL, error TEXT NOT NULL DEFAULT '', report_number TEXT NOT NULL DEFAULT '', synced_at TEXT NOT NULL DEFAULT '', error_status INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE INDEX tally_owner ON tally(owner, created_at)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE tally ADD COLUMN synced_at TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE tally ADD COLUMN error_status INTEGER NOT NULL DEFAULT 0")
        }
    }

    @Synchronized fun directory(owner: String): InspectionDirectory? {
        readableDatabase.rawQuery("SELECT body FROM directory WHERE owner=?", arrayOf(owner)).use {
            if (!it.moveToFirst()) return null
            val body = JSONObject(it.getString(0))
            return InspectionDirectory(body.getJSONArray("voyages").objects().map(Voyage::read), body.getJSONArray("makes").strings(), body.getString("updated_at"))
        }
    }
    @Synchronized fun cache(owner: String, directory: InspectionDirectory) {
        val body = JSONObject().put("voyages", JSONArray(directory.voyages.map { it.json() }))
            .put("makes", JSONArray(directory.makes)).put("updated_at", directory.updatedAt)
        writableDatabase.insertWithOnConflict("directory", null, ContentValues().apply {
            put("owner", owner); put("body", body.toString())
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) { "Could not save the voyage directory." } }
    }
    @Synchronized fun list(owner: String): List<LocalTally> {
        readableDatabase.rawQuery("SELECT id,voyage,payload,state,created_at,error,report_number,synced_at,error_status FROM tally WHERE owner=? ORDER BY created_at DESC", arrayOf(owner)).use { cursor ->
            return buildList {
                while (cursor.moveToNext()) add(LocalTally(cursor.getString(0), Voyage.read(JSONObject(cursor.getString(1))), cursor.getString(2), cursor.getString(3), cursor.getString(4), cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getInt(8)))
            }
        }
    }
    @Synchronized fun save(owner: String, voyage: Voyage, form: JSONObject, editingId: String? = null): String {
        check(!voyage.completed) { "This report is completed. Select an open voyage." }
        val id = editingId ?: UUID.randomUUID().toString()
        val payload = vehiclePayload(form, id).toString()
        val db = writableDatabase
        db.beginTransaction()
        try {
            val duplicate = list(owner).any { it.id != id && it.voyage.id == voyage.id && it.data.text("chassis_number") == JSONObject(payload).text("chassis_number") }
            check(!duplicate) { "This chassis is already saved for this voyage on this device. Open it in Saved tally." }
            val values = ContentValues().apply { put("payload", payload); put("voyage", voyage.json().toString()) }
            if (editingId != null) {
                check(db.update("tally", values, "owner=? AND id=? AND state='pending'", arrayOf(owner, id)) == 1) { "This vehicle has already been sent. Its saved submission cannot be changed." }
            } else {
                values.put("id", id); values.put("owner", owner); values.put("state", "pending"); values.put("created_at", Instant.now().toString())
                check(db.insertOrThrow("tally", null, values) != -1L)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return id
    }
    @Synchronized fun deleteUnsent(owner: String, id: String) {
        check(writableDatabase.delete("tally", "owner=? AND id=? AND state='pending'", arrayOf(owner, id)) == 1) { "Only vehicles that have never been sent can be removed." }
    }
    /** Read the latest payload and lock local edits atomically before transmission. */
    @Synchronized fun claim(owner: String, id: String): LocalTally? {
        val item = list(owner).find { it.id == id && it.state != "synced" } ?: return null
        attempted(owner, id)
        return item
    }
    @Synchronized fun attempted(owner: String, id: String) = update(owner, id, "attempted", "", "")
    @Synchronized fun failed(owner: String, id: String, message: String, status: Int = 0) = update(owner, id, "attempted", message, "", status)
    @Synchronized fun synced(owner: String, id: String, report: String) = update(owner, id, "synced", "", report)
    private fun update(owner: String, id: String, state: String, error: String, report: String, status: Int = 0) {
        check(writableDatabase.update("tally", ContentValues().apply {
            put("state", state); put("error", error); put("report_number", report); put("error_status", status)
            if (state == "synced") put("synced_at", Instant.now().toString())
        }, "owner=? AND id=?", arrayOf(owner, id)) == 1) { "Saved vehicle was not found." }
    }
}
