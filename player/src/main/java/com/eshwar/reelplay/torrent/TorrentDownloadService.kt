package com.eshwar.reelplay.torrent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.eshwar.reelplay.R
import com.eshwar.reelplay.ui.formatSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while torrents download with the app in the background, and shows
 * their combined progress. Stops itself once nothing is downloading.
 */
class TorrentDownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        TorrentDownloads.onFinished = { name -> notifyFinished(name) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(
                this, ONGOING_ID, ongoing("Starting downloads…", null),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (e: Exception) {
            // Refused (e.g. Android 15's daily data-sync allowance is used up). Downloads still
            // run while the app is open; just don't take the app down over a notification.
            Log.w("TorrentDownloadService", "Couldn't go foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        holdLocks()
        if (!started) {
            started = true
            scope.launch {
                TorrentDownloads.items.collectLatest { items ->
                    try {
                        update(items)
                    } catch (e: Exception) {
                        Log.w("TorrentDownloadService", "Notification update failed", e)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun update(items: List<DownloadItem>) {
        val active = items.filter { it.isActive }
        if (active.isEmpty()) {
            stop()
            return
        }
        val total = active.sumOf { it.record.totalBytes }
        val done = active.sumOf { it.doneBytes }
        val rate = active.sumOf { it.downloadRate.toLong() }
        val title = if (active.size == 1) active.first().record.name else "${active.size} torrents downloading"
        val text = "${formatSize(done)} of ${formatSize(total)} · ↓ ${formatSize(rate)}/s"
        notify(ONGOING_ID, ongoing(title, text, if (total > 0) (done * 100 / total).toInt() else null))
    }

    /** Android 15 caps data-sync services at 6 hours a day; downloads stay in the app's list. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stop()
    }

    /**
     * With the screen off, Android lets Wi-Fi drop into power saving and the CPU sleep between
     * packets, which throttles a download to a fraction of the line speed. Held only while this
     * service runs, i.e. while something is actually downloading.
     */
    @Suppress("DEPRECATION")
    private fun holdLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi?.createWifiLock(mode, "ReelPlay:torrent")?.apply {
                setReferenceCounted(false)
            }
        }
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ReelPlay:torrent")
                ?.apply { setReferenceCounted(false) }
        }
        try {
            wifiLock?.acquire()
            // Bounded, and renewed on every start command, so a lost stop can't drain the battery.
            wakeLock?.acquire(LOCK_TIMEOUT_MS)
        } catch (_: Exception) {
        }
    }

    private fun releaseLocks() {
        try {
            wifiLock?.takeIf { it.isHeld }?.release()
            wakeLock?.takeIf { it.isHeld }?.release()
        } catch (_: Exception) {
        }
    }

    private fun stop() {
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun openDownloads(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, DownloadsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ongoing(title: String, text: String?, percent: Int? = null) =
        NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openDownloads())
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply { if (percent != null) setProgress(100, percent, false) else setProgress(0, 0, true) }
            .build()

    private fun notifyFinished(name: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle("Download complete")
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openDownloads())
            .build()
        notify(name.hashCode(), n)
    }

    private fun notify(id: Int, notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(this).notify(id, notification)
    }

    companion object {
        private const val ONGOING_ID = 4201
        private const val LOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val CHANNEL_PROGRESS = "torrent_progress"
        private const val CHANNEL_DONE = "torrent_done"

        /** Starts (or pokes) the service. Harmless if it's already running. */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TorrentDownloadService::class.java))
            } catch (_: Exception) {
                // Not allowed from the background right now; the next time the app is opened
                // it tries again, and downloads keep going while the app is on screen anyway.
            }
        }

        private fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROGRESS, "Torrent downloads", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
    }
}
