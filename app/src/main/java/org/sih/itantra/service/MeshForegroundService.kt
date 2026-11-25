package org.sih.itantra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.sih.itantra.ItantraApplication
import org.sih.itantra.MainActivity
import org.sih.itantra.R

/**
 * Keeps the mesh network (BLE/Wi-Fi Direct discovery, packet receive, SOS listening) alive after
 * the user leaves or swipes away the app. Without this, [org.sih.itantra.domain.repository.CommunicationRepository]
 * is just an Application-owned object with no claim on the process: Android suspends its BLE/Wi-Fi
 * callbacks within minutes of losing UI focus and kills the process outright once the task is
 * removed, so incoming SOS broadcasts from other mesh nodes silently stop arriving.
 *
 * This service does not duplicate any mesh logic - it only holds a foreground-service claim (via
 * the mandatory ongoing notification) and a wake lock so the OS keeps the process and its
 * coroutines/radios running, and posts a high-priority alert whenever [ItantraApplication]'s
 * repository reports an incoming SOS.
 */
class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_STATUS = "mesh_status"
        private const val CHANNEL_SOS = "sos_alerts"
        private const val NOTIF_ID_STATUS = 1001
        private const val EXTRA_OPEN_EMERGENCY = "open_emergency"
        private const val WAKE_LOCK_TAG = "iTANTRA:MeshListenerWakeLock"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun emergencyOpenIntentExtra(): String = EXTRA_OPEN_EMERGENCY
    }

    private var serviceScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var sosNotifCounter = 2000

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(NOTIF_ID_STATUS, buildStatusNotification())
        acquireWakeLock()

        val job = SupervisorJob()
        val scope = CoroutineScope(
            job + Dispatchers.Default + CoroutineExceptionHandler { _, t ->
                android.util.Log.e("MeshForegroundService", "Background error: ${t.message}", t)
            }
        )
        serviceScope = scope

        val repository = (application as ItantraApplication).communicationRepository
        scope.launch {
            repository.sosAlerts.collect { event ->
                postSosAlert(event.senderName, event.message)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY: if the OS still kills this process under memory pressure, it recreates the
        // service (without the original intent) as soon as resources free up, resuming mesh listening.
        return START_STICKY
    }

    /**
     * Many OEM launchers (and stock Android without a foreground service) treat "swipe app away"
     * as "kill everything". Since this is a proper foreground service that is NOT torn down here,
     * the process - and with it the mesh listener and SOS receiver - keeps running.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIF_ID_STATUS, buildStatusNotification())
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        serviceScope?.cancel()
        serviceScope = null
        releaseWakeLock()
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Throwable) {}
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}
        wakeLock = null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        val statusChannel = NotificationChannel(
            CHANNEL_STATUS, "Mesh Network Status", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows that iTANTRA is listening for mesh messages and SOS alerts"
            setShowBadge(false)
        }
        nm.createNotificationChannel(statusChannel)

        val sosChannel = NotificationChannel(
            CHANNEL_SOS, "SOS Alerts", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Critical emergency alerts from nearby devices"
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
            enableLights(true)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(sosChannel)
    }

    private fun openAppIntent(openEmergency: Boolean): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (openEmergency) putExtra(EXTRA_OPEN_EMERGENCY, true)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(this, if (openEmergency) 1 else 0, intent, flags)
    }

    private fun buildStatusNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle("iTANTRA mesh active")
            .setContentText("Listening for messages and SOS alerts from nearby devices")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openAppIntent(openEmergency = false))
            .build()

    private fun postSosAlert(senderName: String, message: String) {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_SOS)
                .setContentTitle("🆘 SOS from $senderName")
                .setContentText(message.ifBlank { "Emergency assistance requested" })
                .setStyle(NotificationCompat.BigTextStyle().bigText(message.ifBlank { "Emergency assistance requested" }))
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setFullScreenIntent(openAppIntent(openEmergency = true), true)
                .setContentIntent(openAppIntent(openEmergency = true))
                .build()
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(sosNotifCounter++, notification)
        } catch (t: Throwable) {
            android.util.Log.e("MeshForegroundService", "Failed to post SOS alert: ${t.message}", t)
        }
    }
}
