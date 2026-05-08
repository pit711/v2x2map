package org.opentrafficmap.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

/**
 * Keeps the process alive (and the CPU awake) while the screen is locked.
 * Started by MainActivity when the first connection opens; stopped when the
 * activity is destroyed. Owns a PARTIAL_WAKE_LOCK for the session.
 */
class ReceiverForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "V2X2MAP", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "ITS-G5 Empfang aktiv" }
        )

        val tap = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("V2X2MAP")
            .setContentText("ITS-G5 Empfang läuft")
            .setOngoing(true)
            .setContentIntent(tap)
            .build()

        startForeground(NOTIF_ID, notification)

        val pm = getSystemService(PowerManager::class.java)
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "v2x2map:receiver")
            .also { it.acquire(MAX_SESSION_MS) }

        return START_STICKY
    }

    override fun onDestroy() {
        try { wakeLock?.release() } catch (_: Exception) {}
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    companion object {
        private const val CHANNEL_ID    = "v2x2map_receiver"
        private const val NOTIF_ID      = 1
        private const val MAX_SESSION_MS = 8L * 60 * 60 * 1000   // 8 h safety ceiling
    }
}
