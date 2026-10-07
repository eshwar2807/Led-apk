package com.eshwar.reelplay.web

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.eshwar.reelplay.R
import com.eshwar.reelplay.torrent.DownloadSaver
import com.eshwar.reelplay.torrent.DownloadsActivity
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

/** Downloads one [StreamDownload]: segments, then the MP4, then into Download/ReelPlay. */
class StreamDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        StreamDownloads.load(ctx)
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val d = StreamDownloads.get(id) ?: return Result.failure()
        if (!d.active) return Result.success()
        val notificationId = NOTIFICATION_BASE + (id.hashCode() and 0xffff)
        try {
            setForeground(foregroundInfo(ctx, notificationId, d.title, null))
        } catch (_: Exception) {
            // Not allowed to show a foreground service right now; carry on as plain work.
        }
        val dir = StreamDownloads.workDir(ctx, id).apply { mkdirs() }
        try {
            val fetcher = HttpFetcher(d.pageUrl)
            val video = Hls.parseMedia(fetcher.text(d.playlistUrl), d.playlistUrl)
            val audio = d.audioUrl?.let { Hls.parseMedia(fetcher.text(it), it) }
            val videoPieces = video.segments.size + (if (video.initUrl != null) 1 else 0)
            val audioPieces = audio?.let { it.segments.size + (if (it.initUrl != null) 1 else 0) } ?: 0
            StreamDownloads.set(ctx, id) { it.copy(state = StreamDownload.State.DOWNLOADING, totalPieces = videoPieces + audioPieces, error = null) }

            val downloader = HlsDownloader(fetcher)
            val videoFile = File(dir, "video.part")
            var lastNotify = 0L
            fun report(done: Int, bytes: Long) {
                StreamDownloads.set(ctx, id, force = false) { it.copy(donePieces = done, bytes = bytes) }
                val now = System.currentTimeMillis()
                if (now - lastNotify > 1_000) {
                    lastNotify = now
                    notify(ctx, notificationId, d.title, done to (videoPieces + audioPieces))
                }
            }
            downloader.download(video, videoFile, File(dir, "video.state")) { p -> report(p.donePieces, p.bytes) }
            var audioFile: File? = null
            if (audio != null) {
                val videoBytes = videoFile.length()
                audioFile = File(dir, "audio.part")
                downloader.download(audio, audioFile, File(dir, "audio.state")) { p ->
                    report(videoPieces + p.donePieces, videoBytes + p.bytes)
                }
            }

            StreamDownloads.set(ctx, id) { it.copy(state = StreamDownload.State.MERGING) }
            val mp4 = File(dir, "video.mp4")
            val (result, mime, ext) = try {
                StreamMuxer.toMp4(videoFile, audioFile, mp4)
                Triple(mp4, "video/mp4", "mp4")
            } catch (e: Exception) {
                mp4.delete()
                // Without a separate audio track the raw download is itself a playable file.
                if (audioFile != null) throw IOException("Couldn't combine the video and audio: ${e.message}")
                if (video.initUrl != null) Triple(videoFile, "video/mp4", "mp4") else Triple(videoFile, "video/mp2t", "ts")
            }

            StreamDownloads.set(ctx, id) { it.copy(state = StreamDownload.State.SAVING) }
            val name = d.title.substringBeforeLast('.').ifBlank { "video" }.take(100) + " (${d.quality.substringBefore(' ')}).$ext"
            val (uri, location) = DownloadSaver.saveOne(ctx, result, name, mime)
            if (!location.startsWith("ReelPlay")) {
                dir.deleteRecursively()
            } else {
                // Kept in app storage: drop only the leftovers.
                dir.listFiles()?.filter { it != result }?.forEach { it.delete() }
            }
            StreamDownloads.set(ctx, id) {
                it.copy(state = StreamDownload.State.DONE, savedUri = uri.toString(), location = location, donePieces = it.totalPieces)
            }
            notifyDone(ctx, notificationId, d.title, "Saved to $location")
            return Result.success()
        } catch (e: CancellationException) {
            // Cancelled by the user (already marked) or stopped by the system (WorkManager reruns it).
            throw e
        } catch (e: UnsupportedStreamException) {
            fail(ctx, id, notificationId, d.title, e.message ?: "This stream can't be downloaded")
            return Result.failure()
        } catch (e: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) {
                StreamDownloads.set(ctx, id) { it.copy(state = StreamDownload.State.QUEUED, error = "Retrying: ${e.message}") }
                return Result.retry()
            }
            fail(ctx, id, notificationId, d.title, e.message ?: "Download failed")
            return Result.failure()
        }
    }

    private fun fail(ctx: Context, id: String, notificationId: Int, title: String, message: String) {
        StreamDownloads.set(ctx, id) { it.copy(state = StreamDownload.State.FAILED, error = message) }
        notifyDone(ctx, notificationId, title, "Download failed: $message")
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val d = inputData.getString(KEY_ID)?.let(StreamDownloads::get)
        return foregroundInfo(applicationContext, NOTIFICATION_BASE, d?.title ?: "Video", null)
    }

    companion object {
        const val KEY_ID = "id"
        private const val CHANNEL = "stream_downloads"
        private const val NOTIFICATION_BASE = 40_000
        private const val MAX_ATTEMPTS = 5

        private fun channel(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                    NotificationChannel(CHANNEL, "Video downloads", NotificationManager.IMPORTANCE_LOW),
                )
            }
        }

        private fun builder(ctx: Context, title: String) = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(title)
            .setOnlyAlertOnce(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    ctx, 0, DownloadsActivity.intent(ctx),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )

        fun foregroundInfo(ctx: Context, id: Int, title: String, progress: Pair<Int, Int>?): ForegroundInfo {
            channel(ctx)
            val n = builder(ctx, title).setOngoing(true).setContentText("Downloading video")
                .setProgress(progress?.second ?: 0, progress?.first ?: 0, progress == null).build()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                ForegroundInfo(id, n)
            }
        }

        private fun notify(ctx: Context, id: Int, title: String, progress: Pair<Int, Int>) {
            channel(ctx)
            val n = builder(ctx, title).setOngoing(true)
                .setContentText("${progress.first * 100 / progress.second.coerceAtLeast(1)}%")
                .setProgress(progress.second, progress.first, false).build()
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(id, n)
            } catch (_: SecurityException) {
            }
        }

        private fun notifyDone(ctx: Context, id: Int, title: String, text: String) {
            channel(ctx)
            val n = builder(ctx, title).setContentText(text).setAutoCancel(true).build()
            try {
                // A different id from the foreground one, so it stays after the work ends.
                ctx.getSystemService(NotificationManager::class.java).notify(id + 1, n)
            } catch (_: SecurityException) {
            }
        }
    }
}
