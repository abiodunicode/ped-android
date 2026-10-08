package com.ped.mobile.sync

import android.content.Context
import com.ped.mobile.data.QueueStore
import com.ped.mobile.inspection.*
import com.ped.mobile.network.ApiClient
import com.ped.mobile.network.InspectionApiException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** All automatic and manual evidence uploads share this lock and live status. */
object SyncCoordinator {
    data class Status(val owner: String = "", val running: Boolean = false, val message: String = "", val error: Boolean = false, val revision: Long = 0)
    data class Outcome(val retry: Boolean = false)
    private val lock = Mutex()
    private val mutableStatus = MutableStateFlow(Status())
    val status = mutableStatus.asStateFlow()

    suspend fun run(context: Context, base: String, token: String, owner: String, automatic: Boolean,
                    onlyId: String? = null, stillAllowed: () -> Boolean = { true }): Outcome = lock.withLock {
        withContext(Dispatchers.IO) {
            val repo = InspectionRepository.get(context, base)
            val queue = QueueStore(context, owner)
            var started = false
            var retry = false
            var sent = 0
            val notices = SyncNotifications(context)
            fun update(message: String, running: Boolean = true, error: Boolean = false) {
                mutableStatus.value = Status(owner, running, message, error, mutableStatus.value.revision + 1)
                if (started) notices.show(message, running)
            }
            suspend fun guard() {
                currentCoroutineContext().ensureActive()
                check(stillAllowed()) { "Sync paused. Your saved records are kept." }
            }
            try {
                guard()
                val receipts = queue.history().map { it.id }.toSet()
                queue.loadQueue().filter { it.id in receipts }.forEach { queue.remove(it.id) }
                if (automatic && queue.loadQueue().none { it.error.isBlank() } && repo.store.list(owner).none { it.state != "synced" && it.errorStatus !in setOf(400, 401, 403, 404, 409, 422) }) return@withContext Outcome()
                if (!automatic) update("Checking the CIMS server…")
                // Verifies both server reachability and current permissions before announcing uploads.
                val actor = repo.verifyAccount(token)
                check(repo.owner(actor) == owner) { "Sign in with the account that saved these records." }
                guard()
                val evidence = queue.loadQueue().filter { actor.canCapture(it.kind) && (onlyId == null || it.id == onlyId) && (!automatic || it.error.isBlank()) }.reversed()
                val tally = if (onlyId == null && actor.can("inspections.create")) repo.store.list(owner).filter { it.state != "synced" && (!automatic || it.errorStatus !in setOf(400, 401, 403, 404, 409, 422)) } else emptyList()
                val total = evidence.size + tally.size
                if (total == 0) { if (!automatic) update("No saved records are available to upload for your current role.", false); return@withContext Outcome() }
                started = true
                update(if (automatic) "Internet detected. Syncing $total saved records…" else "Syncing $total saved records…")
                val startedAt = System.currentTimeMillis()
                for ((index, item) in evidence.withIndex()) {
                    guard()
                    if (automatic && System.currentTimeMillis() - startedAt > 180_000) { retry = true; break }
                    val data = queue.loadCaptureForSync(item.id) ?: continue
                    update("Uploading evidence ${index + 1} of ${evidence.size}…")
                    // The deployed evidence endpoints are not idempotent. Persist before POST;
                    // interruption/uncertain response must require manual review, never silent replay.
                    queue.failed(item.id, "Upload confirmation pending. Check the server before retrying this record.")
                    val result = ApiClient(base).syncCapture(token, data)
                    if (result.isSuccess) {
                        queue.uploaded(item, result.getOrThrow())
                        sent++
                    } else {
                        val error = result.exceptionOrNull()
                        val status = (error as? InspectionApiException)?.status
                        queue.failed(item.id, when (status) {
                            401 -> "Sign in again with this account, then retry."
                            403, 409, 422 -> "Needs review: ${error?.message.orEmpty()}"
                            else -> "Upload was not confirmed. Check the server before retrying. ${error?.message.orEmpty()}"
                        })
                        if (status == 401 || status == 403) break
                        if (status == null || status >= 500 || status == 429) { retry = true; break }
                    }
                }
                guard()
                if (tally.isNotEmpty()) {
                    val before = repo.store.list(owner).count { it.state == "synced" }
                    repo.sync(token, actor, automatic = automatic, beforeEach = { guard() }, maxItems = if (automatic) 10 else Int.MAX_VALUE) { index, count ->
                        update("Uploading tally $index of $count…")
                    }
                    val after = repo.store.list(owner)
                    sent += after.count { it.state == "synced" } - before
                    retry = retry || after.any { it.state != "synced" && it.errorStatus !in setOf(400, 401, 403, 404, 409, 422) }
                }
                val remaining = queue.loadQueue().count { actor.canCapture(it.kind) } + if (actor.can("inspections.create")) repo.store.list(owner).count { it.state != "synced" } else 0
                update(if (remaining == 0) "$sent records synced. All saved work is uploaded."
                    else "$sent records synced. $remaining remain on this device. Open Uploads for details.", false, remaining > 0)
                Outcome(retry)
            } catch (e: CancellationException) {
                update("Sync paused. Saved records remain on this device.", false)
                throw e
            } catch (e: Exception) {
                update("Sync paused. ${e.message ?: "Check your connection."}", false, true)
                Outcome(e !is InspectionApiException || e.status >= 500 || e.status == 429)
            } finally { queue.close() }
        }
    }
}
