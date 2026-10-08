package com.ped.mobile.inspection

import android.content.Context
import com.ped.mobile.network.ApiClient
import com.ped.mobile.network.InspectionApiException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

class InspectionRepository(context: Context, private val api: ApiClient, private val baseUrl: String) {
    val store = InspectionStore(context.applicationContext)
    val catalogue = VehicleCatalogueStore(context.applicationContext)
    private val sessions = context.getSharedPreferences("inspection-accounts", Context.MODE_PRIVATE)
    private fun binding(token: String): String = MessageDigest.getInstance("SHA-256")
        .digest("$baseUrl\n$token".toByteArray()).joinToString("") { "%02x".format(it) }
    fun owner(account: InspectionAccount) = "${baseUrl.trimEnd('/')}\n${account.id}"
    fun cachedAccount(token: String): InspectionAccount? = sessions.getString(binding(token), null)?.let {
        val json = JSONObject(it); InspectionAccount(json.getLong("id"), json.getString("email"), json.optJSONArray("permissions")?.strings()?.toSet(), json.optString("name"), json.optString("type"))
    }
    fun verifyAccount(token: String): InspectionAccount {
        val profile = api.inspectionRequest(token, "/profile")
        val account = InspectionAccount(profile.getLong("id"), profile.getString("email"), profile.optJSONArray("permissions")?.strings()?.toSet() ?: emptySet(), profile.optString("name"), profile.optString("type"))
        check(account.id > 0)
        check(sessions.edit().putString(binding(token), JSONObject().put("id", account.id).put("email", account.email).put("name", account.name).put("type", account.accountType).put("permissions", org.json.JSONArray(account.permissions?.toList())).toString()).commit())
        return account
    }
    fun refresh(token: String, account: InspectionAccount): InspectionDirectory {
        check(verifyAccount(token).id == account.id) { "Sign in with the account that prepared this device." }
        val voyages = mutableListOf<Voyage>()
        var page = 1
        do {
            val result = api.inspectionRequest(token, "/inspection-voyages?page=$page")
            voyages += result.getJSONArray("data").objects().map(Voyage::read)
            val last = result.getInt("last_page")
            page++
        } while (page <= last)
        refreshCatalogue(token, account)
        val options = api.inspectionRequest(token, "/inspection-options")
        val directory = InspectionDirectory(voyages.distinctBy { it.id }, options.getJSONArray("vehicle_makes").strings(), Instant.now().toString())
        store.cache(owner(account), directory)
        return directory
    }
    @Synchronized fun refreshCatalogue(token: String, account: InspectionAccount) {
        val owner = owner(account)
        var pages = 0
        do {
            val state = catalogue.state(owner)
            var cursor = state.second
            var page = api.inspectionRequest(token, "/vehicle-catalogue/sync?cursor=$cursor&limit=250")
            if (state.first.isNotBlank() && state.first != page.getString("catalogue_id")) {
                cursor = 0
                page = api.inspectionRequest(token, "/vehicle-catalogue/sync?cursor=0&limit=250")
            }
            check(!page.getBoolean("has_more") || page.getLong("next_cursor") > cursor) { "Catalogue download made no progress. Retry the refresh." }
            catalogue.apply(owner, page, cursor)
            check(++pages <= 2000) { "Catalogue is still changing. Refresh again to continue downloading." }
        } while (page.getBoolean("has_more"))
    }
    /** Manual and automatic uploads share a lock and stable submission IDs. */
    suspend fun sync(token: String, account: InspectionAccount, automatic: Boolean = false, beforeEach: suspend () -> Unit = {}, maxItems: Int = Int.MAX_VALUE, progress: suspend (Int, Int) -> Unit): String = syncLock.withLock {
        val verified = verifyAccount(token)
        check(verified.id == account.id) { "Sign in with the original inspector account to sync these vehicles." }
        check(verified.can("inspections.create")) { "Your role cannot upload inspection tallies. Saved work is kept." }
        val owner = owner(account)
        val queue = store.list(owner).filter { it.state != "synced" && (!automatic || it.errorStatus !in setOf(400, 401, 403, 404, 409, 422)) }.reversed().take(maxItems)
        var sent = 0
        var failures = 0
        for ((index, snapshot) in queue.withIndex()) {
            beforeEach()
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val item = store.claim(owner, snapshot.id) ?: continue
            progress(index + 1, queue.size)
            try {
                // Opening is idempotent by voyage on both Go and Laravel.
                val report = api.inspectionRequest(token, "/inspection-reports", "POST", JSONObject().put("inspection_voyage_id", item.voyage.id))
                // Still replay a prior submission after completion: the server checks UUID before the lock.
                val result = api.inspectionRequest(token, "/inspection-reports/${report.getLong("id")}/vehicles", "POST", JSONObject(item.payload))
                check(result.getJSONObject("vehicle").getString("submission_id") == item.id) { "The server did not confirm this submission. Retry safely." }
                store.synced(owner, item.id, report.getString("report_number"))
                sent++
            } catch (error: kotlinx.coroutines.CancellationException) { throw error
            } catch (error: Exception) {
                store.failed(owner, item.id, error.message ?: "Sync failed. Your vehicle remains saved on this device.", (error as? InspectionApiException)?.status ?: 0)
                failures++
                if (error !is InspectionApiException || error.status == 401 || error.status >= 500) break
            }
        }
        val remaining = store.list(owner).count { it.state != "synced" }
        "$sent synced · $remaining still on device" + if (failures > 0) ". Review the message on each vehicle, then retry." else "."
    }
    companion object {
        private val syncLock = Mutex()
        private val instances = java.util.concurrent.ConcurrentHashMap<String, InspectionRepository>()
        fun get(context: Context, baseUrl: String): InspectionRepository = instances.getOrPut(baseUrl) {
            InspectionRepository(context.applicationContext, ApiClient(baseUrl), baseUrl)
        }
    }
}
