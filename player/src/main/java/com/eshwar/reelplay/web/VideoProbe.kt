package com.eshwar.reelplay.web

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** What a found video turned out to be, for its tile. Null fields are unknown. */
data class VideoInfo(
    val durationMs: Long? = null,
    val width: Int = 0,
    val height: Int = 0,
    /** Exact for files; for streams an estimate from the best quality's bitrate. */
    val sizeBytes: Long? = null,
    val sizeIsEstimate: Boolean = false,
    /** Stream qualities, best first; empty for files. */
    val variants: List<Hls.Variant> = emptyList(),
    val live: Boolean = false,
    val frame: Bitmap? = null,
) {
    val resolution: String? get() {
        val p = if (width > 0 && height > 0) minOf(width, height) else height
        return if (p > 0) "${p}p" else null
    }
}

/**
 * Looks inside found videos so the user can tell them apart: length, resolution, size and a
 * preview (the page's poster, or a frame from the video itself). Results are cached; only a
 * few probes run at once.
 */
object VideoProbe {

    private val infos = ConcurrentHashMap<String, VideoInfo>()
    private val images = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val gate = Semaphore(3)

    fun cached(url: String): VideoInfo? = infos[url]

    suspend fun probe(video: FoundVideo, pageUrl: String?): VideoInfo {
        infos[video.url]?.let { return it }
        val info = gate.withPermit {
            withContext(Dispatchers.IO) {
                try {
                    when (video.kind) {
                        FoundVideo.Kind.FILE -> probeFile(video, pageUrl)
                        FoundVideo.Kind.STREAM -> if (video.url.substringBefore('?').endsWith(".m3u8", true)) {
                            probeHls(video, pageUrl)
                        } else {
                            VideoInfo(frame = video.poster?.let { image(it, pageUrl) })
                        }
                        else -> VideoInfo(frame = video.poster?.let { image(it, pageUrl) })
                    }
                } catch (_: Exception) {
                    VideoInfo(frame = video.poster?.let { runCatching { image(it, pageUrl) }.getOrNull() })
                }
            }
        }
        infos[video.url] = info
        return info
    }

    private fun headers(pageUrl: String?) = buildMap {
        put("User-Agent", HttpFetcher.USER_AGENT)
        pageUrl?.let { put("Referer", it) }
    }

    private fun probeFile(video: FoundVideo, pageUrl: String?): VideoInfo {
        val size = contentLength(video.url, pageUrl)
        val poster = video.poster?.let { image(it, pageUrl) }
        val meta = retrieve(video.url, pageUrl, wantFrame = poster == null)
        return meta.copy(sizeBytes = size, frame = poster ?: meta.frame)
    }

    private fun probeHls(video: FoundVideo, pageUrl: String?): VideoInfo {
        val fetcher = HttpFetcher(pageUrl, timeoutMs = 15_000)
        val text = fetcher.text(video.url)
        val variants = if (Hls.isMaster(text)) Hls.downloadChoices(Hls.parseMaster(text, video.url)) else emptyList()
        // The smallest quality is enough to learn the length and grab a frame.
        val mediaUrl = variants.minByOrNull { it.bandwidth.takeIf { b -> b > 0 } ?: Long.MAX_VALUE }?.url ?: video.url
        val media = if (variants.isEmpty()) Hls.parseMedia(text, video.url) else Hls.parseMedia(fetcher.text(mediaUrl), mediaUrl)
        val durationMs = (media.durationSec * 1000).toLong().takeIf { it > 0 && media.ended }
        val best = variants.firstOrNull()
        val poster = video.poster?.let { image(it, pageUrl) }
        // A frame from the first segment, when it's a plain MPEG-TS file the phone can open alone.
        val frame = poster ?: media.segments.firstOrNull()?.takeIf { media.initUrl == null && it.range == null }
            ?.let { retrieve(it.url, pageUrl, wantFrame = true).frame }
        val estimate = if (best != null && best.bandwidth > 0 && durationMs != null) best.bandwidth * durationMs / 8000 else null
        return VideoInfo(
            durationMs = durationMs,
            width = best?.width ?: 0,
            height = best?.height ?: 0,
            sizeBytes = estimate,
            sizeIsEstimate = true,
            variants = variants,
            live = !media.ended,
            frame = frame,
        )
    }

    private fun contentLength(url: String, pageUrl: String?): Long? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        headers(pageUrl).forEach(conn::setRequestProperty)
        conn.setRequestProperty("Range", "bytes=0-0")
        return try {
            when (conn.responseCode) {
                206 -> conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                200 -> conn.contentLengthLong.takeIf { it > 0 }
                else -> null
            }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun retrieve(url: String, pageUrl: String?, wantFrame: Boolean): VideoInfo {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(url, headers(pageUrl))
            val duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            var w = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) w = h.also { h = w }
            val frame = if (wantFrame) {
                // A little way in: the very first frame is often black.
                val at = ((duration ?: 0) * 1000 / 10).coerceIn(0, 5_000_000)
                mmr.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let(::shrink)
            } else {
                null
            }
            VideoInfo(durationMs = duration, width = w, height = h, frame = frame)
        } catch (_: Exception) {
            VideoInfo()
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun image(url: String, pageUrl: String?): Bitmap? {
        images.get(url)?.let { return it }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        headers(pageUrl).forEach(conn::setRequestProperty)
        return try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 320) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?.let(::shrink)?.also { images.put(url, it) }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun shrink(b: Bitmap): Bitmap {
        if (b.width <= 360) return b
        val h = (b.height * 360f / b.width).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(b, 360, h, true)
    }
}
