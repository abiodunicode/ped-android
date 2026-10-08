package com.ped.mobile.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ped.mobile.MainActivity

class SyncNotifications(private val context: Context) {
    fun show(message: String, running: Boolean) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("cims-sync", "Saved record uploads", NotificationManager.IMPORTANCE_LOW))
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = NotificationCompat.Builder(context, "cims-sync")
            .setSmallIcon(android.R.drawable.stat_sys_upload).setContentTitle("CIMS uploads")
            .setContentText(message).setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(open).setOnlyAlertOnce(true).setOngoing(running).setAutoCancel(!running)
            .setProgress(0, 0, running).setTimeoutAfter(if (running) 600_000 else 0).build()
        runCatching { NotificationManagerCompat.from(context).notify(2408, notice) }
    }
}
