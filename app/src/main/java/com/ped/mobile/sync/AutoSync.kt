package com.ped.mobile.sync

import android.content.Context
import androidx.work.*
import com.ped.mobile.data.SessionStore
import com.ped.mobile.inspection.InspectionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object AutoSync {
    data class Session(val base: String, val owner: String, val token: String)
    @Volatile private var active: Session? = null
    private const val TAG = "cims-auto-sync"
    private fun prefs(context: Context) = context.getSharedPreferences("cims-sync-session", Context.MODE_PRIVATE)
    private fun name(owner: String) = "$TAG-${MessageDigest.getInstance("SHA-256").digest(owner.toByteArray()).joinToString("") { "%02x".format(it) }}"

    fun activate(context: Context, base: String, owner: String, token: String) {
        val next = Session(base, owner, token)
        if (active?.owner != owner) WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        active = next
        prefs(context).edit().putString("base", base).putString("owner", owner).commit()
        schedule(context, periodic = true)
    }
    fun deactivate(context: Context) {
        active = null
        prefs(context).edit().clear().commit()
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
    }
    fun session(context: Context): Session? {
        active?.let { return it }
        val prefs = prefs(context)
        val base = prefs.getString("base", null) ?: return null
        val owner = prefs.getString("owner", null) ?: return null
        val sessions = SessionStore(context)
        if (!sessions.readRememberMe()) return null
        val token = sessions.readToken() ?: return null
        val repo = InspectionRepository.get(context, base)
        val account = repo.cachedAccount(token) ?: return null
        return if (repo.owner(account) == owner) Session(base, owner, token) else null
    }
    fun isCurrent(context: Context, expected: Session) = session(context) == expected
    fun settingsChanged(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
        if (SyncSettings(context).enabled) schedule(context, periodic = true)
    }
    fun schedule(context: Context, periodic: Boolean = false) {
        val session = session(context) ?: return
        val settings = SyncSettings(context)
        if (!settings.enabled) return
        val constraints = Constraints.Builder().setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED).build()
        val data = workDataOf("owner" to session.owner)
        val work = OneTimeWorkRequestBuilder<AutoSyncWorker>().setInputData(data).setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).build()
        val manager = WorkManager.getInstance(context)
        manager.enqueueUniqueWork(name(session.owner), ExistingWorkPolicy.KEEP, work)
        // Recovery for work saved during a running upload and for process death/reboot.
        if (periodic) manager.enqueueUniquePeriodicWork(name(session.owner) + "-recovery", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoSyncWorker>(15, TimeUnit.MINUTES).setInputData(data)
                .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).addTag(TAG).build())
    }
}

class AutoSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val session = AutoSync.session(applicationContext) ?: return@withContext Result.success()
        if (session.owner != inputData.getString("owner")) return@withContext Result.success()
        val settings = SyncSettings(applicationContext)
        if (!settings.enabled) return@withContext Result.success()
        if (!settings.allowsNetwork(applicationContext)) return@withContext Result.retry()
        val outcome = SyncCoordinator.run(applicationContext, session.base, session.token, session.owner, true) {
            AutoSync.isCurrent(applicationContext, session) && SyncSettings(applicationContext).enabled && SyncSettings(applicationContext).allowsNetwork(applicationContext)
        }
        if (outcome.retry && AutoSync.isCurrent(applicationContext, session)) Result.retry() else Result.success()
    }
}
