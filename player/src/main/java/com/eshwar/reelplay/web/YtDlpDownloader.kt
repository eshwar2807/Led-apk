package com.eshwar.reelplay.web

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Downloads YouTube and web videos (including 4K/2K/1080p with audio muxing) using
 * the embedded yt-dlp & FFmpeg engine (the same engine used by Seal).
 */
object YtDlpDownloader {

    private const val TAG = "YtDlpDownloader"
    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            var ytdlReady = false
            try {
                YoutubeDL.getInstance().init(context.applicationContext)
                ytdlReady = true
                Log.d(TAG, "YoutubeDL initialized successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize YoutubeDL: ${e.message}", e)
                throw IllegalStateException("yt-dlp engine initialization failed: ${e.message}", e)
            }
            try {
                FFmpeg.getInstance().init(context.applicationContext)
                Log.d(TAG, "FFmpeg initialized successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize FFmpeg: ${e.message}", e)
            }
            initialized = ytdlReady
        }
    }

    data class YtQuality(
        val id: String,
        val label: String,
        val formatSpec: String,
        val isAudioOnly: Boolean = false,
    )

    data class YtDetails(
        val id: String,
        val url: String,
        val title: String,
        val uploader: String?,
        val durationSeconds: Long,
        val thumbnailUrl: String?,
        val qualities: List<YtQuality>,
    )

    /**
     * Fetches video metadata and available qualities using yt-dlp.
     */
    suspend fun fetchDetails(context: Context, url: String): YtDetails = withContext(Dispatchers.IO) {
        init(context)
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-check-certificates")
            addOption("--geo-bypass")
        }
        val info: VideoInfo = try {
            YoutubeDL.getInstance().getInfo(request)
        } catch (e: Exception) {
            Log.w(TAG, "Initial getInfo failed: ${e.message}. Attempting yt-dlp binary update...")
            try {
                YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
                YoutubeDL.getInstance().getInfo(request)
            } catch (_: Exception) {
                Log.e(TAG, "Failed to fetch video info: ${e.message}", e)
                throw e
            }
        }

        val title = info.title ?: "YouTube Video"
        val duration = info.duration.toLong()
        val thumb = info.thumbnail
        val uploader = info.uploader

        val availableHeights = info.formats?.mapNotNull { it.height }?.toSet().orEmpty()
        val qualities = mutableListOf<YtQuality>()

        // 4K (2160p)
        if (availableHeights.any { it >= 2160 }) {
            qualities.add(YtQuality("2160", "4K (2160p)", "bestvideo[height<=2160]+bestaudio/best"))
        }
        // 2K / 1440p
        if (availableHeights.any { it in 1440..2159 }) {
            qualities.add(YtQuality("1440", "2K (1440p)", "bestvideo[height<=1440]+bestaudio/best"))
        }
        // 1080p Full HD
        if (availableHeights.any { it in 1080..1439 } || availableHeights.isEmpty()) {
            qualities.add(YtQuality("1080", "1080p (Full HD)", "bestvideo[height<=1080]+bestaudio/best"))
        }
        // 720p HD
        if (availableHeights.any { it in 720..1079 } || availableHeights.isEmpty()) {
            qualities.add(YtQuality("720", "720p (HD)", "bestvideo[height<=720]+bestaudio/best"))
        }
        // 480p SD
        if (availableHeights.any { it in 360..719 }) {
            qualities.add(YtQuality("480", "480p", "bestvideo[height<=480]+bestaudio/best"))
        }

        // Always ensure at least best quality option exists
        if (qualities.isEmpty()) {
            qualities.add(YtQuality("best", "Best Available (up to 4K)", "bestvideo+bestaudio/best"))
        }

        // Audio extraction option (MP3)
        qualities.add(YtQuality("audio", "Audio only (MP3)", "bestaudio", isAudioOnly = true))

        YtDetails(
            id = info.id ?: UUID.randomUUID().toString(),
            url = url,
            title = title,
            uploader = uploader,
            durationSeconds = duration,
            thumbnailUrl = thumb,
            qualities = qualities,
        )
    }

    /**
     * Starts downloading the video to Download/ReelPlay.
     */
    fun startDownload(
        context: Context,
        url: String,
        title: String,
        quality: YtQuality,
        onProgress: ((Float, Long) -> Unit)? = null,
    ): String {
        init(context)
        val processId = UUID.randomUUID().toString()
        val downloadDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "ReelPlay")
        if (!downloadDir.exists()) downloadDir.mkdirs()

        val request = YoutubeDLRequest(url).apply {
            addOption("-o", "${downloadDir.absolutePath}/%(title)s.%(ext)s")
            addOption("-f", quality.formatSpec)
            addOption("--no-check-certificates")
            addOption("--geo-bypass")
            if (quality.isAudioOnly) {
                addOption("-x")
                addOption("--audio-format", "mp3")
            } else {
                addOption("--merge-output-format", "mp4")
            }
        }

        kotlin.concurrent.thread(name = "ytdl-$processId") {
            try {
                YoutubeDL.getInstance().execute(request, processId) { progress, etaInSeconds, _ ->
                    onProgress?.invoke(progress, etaInSeconds)
                }
                MediaScannerConnection.scanFile(
                    context.applicationContext,
                    arrayOf(downloadDir.absolutePath),
                    null,
                    null,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Download execution failed for $title", e)
            }
        }
        return processId
    }
}
