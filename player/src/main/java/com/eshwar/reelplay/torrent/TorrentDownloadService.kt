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
import android.os.IBinder
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels(this)
        TorrentDownloads.onFinished = { name -> notifyFinished(name) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, ONGOING_ID, ongoing("Starting downloads…", null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (!started) {
            started = true
            scope.launch {
                TorrentDownloads.items.collectLatest { items ->
                    val active = items.filter { it.isActive }
                    if (active.isEmpty()) {
                        stop()
                        return@collectLatest
                    }
                    val total = active.sumOf { it.record.totalBytes }
                    val done = active.sumOf { it.doneBytes }
                    val rate = active.sumOf { it.downloadRate.toLong() }
                    val title = if (active.size == 1) active.first().record.name else "${active.size} torrents downloading"
                    val text = "${formatSize(done)} of ${formatSize(total)} · ↓ ${formatSize(rate)}/s"
                    notify(ONGOING_ID, ongoing(title, text, if (total > 0) (done * 100 / total).toInt() else null))
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15 caps data-sync services at 6 hours a day; downloads stay in the app's list. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stop()
    }

    private fun stop() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
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
