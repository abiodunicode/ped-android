package com.ped.mobile.scanning

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import java.io.File

internal interface CargoLookup {
    fun exact(candidates: List<String>): Set<String>
    fun similar(candidate: String): List<String>
}

internal fun cargoSignature(value: String): String = value.map {
    when (it) { 'O', 'Q' -> '0'; 'I', 'L' -> '1'; 'B' -> '8'; 'S' -> '5'; 'Z' -> '2'; else -> it }
}.joinToString("")

/** Read-only reference, separate from account-scoped PED and tally storage. Call on IO. */
internal class CargoReference private constructor(private val db: SQLiteDatabase) : CargoLookup {
    override fun exact(candidates: List<String>): Set<String> {
        val result = mutableSetOf<String>()
        candidates.distinct().chunked(100).forEach { batch ->
            val sql = "SELECT cargo_id FROM cargo_reference WHERE cargo_id IN (" + batch.joinToString(",") { "?" } + ")"
            db.rawQuery(sql, batch.toTypedArray()).use {
                while (it.moveToNext()) result.add(it.getString(0))
            }
        }
        return result
    }

    override fun similar(candidate: String): List<String> {
        if (candidate.length !in 8..25) return emptyList()
        val result = mutableListOf<String>()
        db.rawQuery("SELECT cargo_id FROM cargo_reference WHERE signature = ? ORDER BY cargo_id LIMIT 32", arrayOf(cargoSignature(candidate))).use {
            while (it.moveToNext()) {
                val value = it.getString(0)
                if (value.length == candidate.length && value.indices.count { i -> value[i] != candidate[i] } in 1..2) result.add(value)
            }
        }
        return result.take(5)
    }

    companion object {
        @Volatile private var instance: CargoReference? = null
        @Synchronized fun get(context: Context): CargoReference {
            instance?.let { return it }
            val metadata = org.json.JSONObject(context.assets.open("cargo-reference-v1.json").bufferedReader().use { it.readText() })
            val hash = metadata.getString("asset_sha256")
            val file = File(context.noBackupFilesDir, "cargo-reference-" + hash + ".db")
            if (!file.exists()) {
                val temp = File.createTempFile("cargo-reference-", ".tmp", context.noBackupFilesDir)
                try {
                    context.assets.open("cargo-reference-v1.db").use { source -> temp.outputStream().use { source.copyTo(it) } }
                    check(temp.renameTo(file)) { "Could not prepare offline reference" }
                } finally { temp.delete() }
            }
            val opened = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            try {
                check(opened.version == 1)
                opened.rawQuery("SELECT cargo_id FROM cargo_reference LIMIT 1", null).use { check(it.moveToFirst()) }
                return CargoReference(opened).also { instance = it }
            } catch (e: Exception) { opened.close(); throw e }
        }
    }
}
