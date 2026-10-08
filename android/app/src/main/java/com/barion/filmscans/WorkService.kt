package com.barion.filmscans

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app running while it unzips or converts, so switching to another app or turning the
 * screen off doesn't stop a long job. The work itself runs in [AppModel]; this only shows its
 * progress in a notification (with a Stop button) and keeps Android from closing the app meanwhile.
 */
class WorkService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            (application as EtTuTiffApp).model.stopWork()
            return START_NOT_STICKY
        }
        val type = when {
            Build.VERSION.SDK_INT >= 35 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            Build.VERSION.SDK_INT >= 29 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            else -> 0
        }
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Working"
        try {
            ServiceCompat.startForeground(this, ID, build(this, title, "", -1f, ongoing = true), type)
        } catch (e: Exception) {
            // Not allowed to run in the background right now: the work carries on while the app is open.
            stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits how long this kind of job may run in a day; stop cleanly rather than be stopped. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        (application as EtTuTiffApp).model.stopWork()
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "work"
        private const val ID = 1
        private const val ACTION_STOP = "stop"
        private const val EXTRA_TITLE = "title"
        @Volatile private var lastUpdate = 0L
        @Volatile private var running = false

        private fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null)
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Unzipping and converting", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Progress while the app works in the background"; setShowBadge(false) })
        }

        private fun build(ctx: Context, title: String, text: String, progress: Float, ongoing: Boolean): Notification {
            channel(ctx)
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val b = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(open)
                .setOnlyAlertOnce(true)
                .setSilent(ongoing)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            if (ongoing) {
                if (progress >= 0) b.setProgress(1000, (progress * 1000).toInt().coerceIn(0, 1000), false) else b.setProgress(0, 0, true)
                val stop = PendingIntent.getService(ctx, 1, Intent(ctx, WorkService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                b.addAction(0, "Stop", stop)
            }
            return b.build()
        }

        fun start(ctx: Context, title: String) {
            running = true
            runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WorkService::class.java).putExtra(EXTRA_TITLE, title))
            }
        }

        /** Progress in the notification, at most a couple of times a second. */
        fun update(ctx: Context, title: String, text: String, progress: Float) {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastUpdate < 500) return
            lastUpdate = now
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID, build(ctx, title, text, progress, ongoing = true)) }
        }

        /** Stops the service and leaves a "done" notification you can tap to see the result. */
        fun finish(ctx: Context, title: String, text: String) {
            running = false
            runCatching { ctx.stopService(Intent(ctx, WorkService::class.java)) }
            // A progress update racing the stop mustn't leave a notification that can't be swiped away.
            runCatching { ctx.getSystemService(NotificationManager::class.java).cancel(ID) }
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID + 1, build(ctx, title, text, -1f, ongoing = false)) }
        }
    }
}
