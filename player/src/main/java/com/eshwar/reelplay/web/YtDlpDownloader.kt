package com.eshwar.reelplay.web

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
            qualities.add(YtQuality("2160", "4K (2160p)", "bestvideo[height<=2160]+bestaudio/best[height<=2160]/best"))
        }
        // 2K / 1440p
        if (availableHeights.any { it in 1440..2159 }) {
            qualities.add(YtQuality("1440", "2K (1440p)", "bestvideo[height<=1440]+bestaudio/best[height<=1440]/best"))
        }
        // 1080p Full HD
        if (availableHeights.any { it in 1080..1439 } || availableHeights.isEmpty()) {
            qualities.add(YtQuality("1080", "1080p (Full HD)", "bestvideo[height<=1080][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=1080]+bestaudio/best[height<=1080]/best"))
        }
        // 720p HD
        if (availableHeights.any { it in 720..1079 } || availableHeights.isEmpty()) {
            qualities.add(YtQuality("720", "720p (HD)", "bestvideo[height<=720][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=720]+bestaudio/best[height<=720]/best"))
        }
        // 480p SD
        if (availableHeights.any { it in 360..719 }) {
            qualities.add(YtQuality("480", "480p", "bestvideo[height<=480][ext=mp4]+bestaudio[ext=m4a]/bestvideo[height<=480]+bestaudio/best[height<=480]/best"))
        }

        // Always ensure at least best quality option exists
        if (qualities.isEmpty()) {
            qualities.add(YtQuality("best", "Best Available (up to 4K)", "bestvideo+bestaudio/best"))
        }

        // Audio extraction option (MP3)
        qualities.add(YtQuality("audio", "Audio only (MP3)", "bestaudio/best", isAudioOnly = true))

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
     * Downloads and merges the video or audio stream into [outputDir] (app-private external storage)
     * so Android Scoped Storage never blocks native yt-dlp/FFmpeg processes, then returns the
     * finished [File] ready for [com.eshwar.reelplay.torrent.DownloadSaver.saveOne].
     */
    fun downloadToDir(
        context: Context,
        processId: String,
        url: String,
        formatSpec: String,
        isAudioOnly: Boolean,
        outputDir: File,
        onProgress: (progressPercent: Float, etaSeconds: Long, isMerging: Boolean) -> Unit,
    ): File {
        init(context)
        if (!outputDir.exists()) outputDir.mkdirs()
        outputDir.listFiles()?.forEach { it.delete() }

        fun buildRequest(fmt: String, mergeFormat: String?, extractMp3: Boolean): YoutubeDLRequest {
            return YoutubeDLRequest(url).apply {
                addOption("--no-playlist")
                addOption("--no-check-certificates")
                addOption("--geo-bypass")
                addOption("--newline")
                addOption("-o", "${outputDir.absolutePath}/download.%(ext)s")
                addOption("-f", fmt)
                if (extractMp3) {
                    addOption("-x")
                    addOption("--audio-format", "mp3")
                } else if (mergeFormat != null) {
                    addOption("--merge-output-format", mergeFormat)
                }
            }
        }

        val primaryRequest = if (isAudioOnly) {
            buildRequest(formatSpec, null, extractMp3 = true)
        } else {
            buildRequest(formatSpec, "mp4/mkv", extractMp3 = false)
        }

        try {
            YoutubeDL.getInstance().execute(primaryRequest, processId) { progress, etaInSeconds, line ->
                val merging = line.contains("[Merger]") || line.contains("[ExtractAudio]") || line.contains("Merging")
                onProgress(progress, etaInSeconds, merging)
            }
        } catch (firstErr: Exception) {
            Log.w(TAG, "Primary download failed (${firstErr.message}); retrying with fallback format...")
            outputDir.listFiles()?.forEach { it.delete() }
            val fallbackRequest = if (isAudioOnly) {
                buildRequest("bestaudio[ext=m4a]/bestaudio/best", null, extractMp3 = false)
            } else {
                buildRequest("best[ext=mp4]/best", null, extractMp3 = false)
            }
            YoutubeDL.getInstance().execute(fallbackRequest, "$processId-fb") { progress, etaInSeconds, line ->
                val merging = line.contains("[Merger]") || line.contains("[ExtractAudio]")
                onProgress(progress, etaInSeconds, merging)
            }
        }

        return outputDir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && it.length() > 0 }
            ?.maxByOrNull { it.length() }
            ?: throw IOException("Download finished, but no output file was created")
    }
}
