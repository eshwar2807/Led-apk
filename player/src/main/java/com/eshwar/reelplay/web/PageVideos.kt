package com.eshwar.reelplay.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/** A video link found on a web page. */
data class FoundVideo(val url: String, val name: String, val kind: Kind) {
    enum class Kind(val label: String) {
        /** A plain file: playable and downloadable. */
        FILE("Video file"),

        /** An HLS/DASH playlist: plays, but isn't one file to download. */
        STREAM("Live/adaptive stream"),
        TORRENT("Torrent"),
        MAGNET("Magnet link"),
    }

    val downloadable: Boolean get() = kind != Kind.STREAM
}

/**
 * Finds the videos a web page links to or embeds: direct video files, `<video>`/`<source>`
 * sources, HLS/DASH playlists, `.torrent` files and magnet links. Only what's in the page's
 * HTML; sites that build their player in script won't list anything.
 */
object PageVideos {

    private val VIDEO_EXT = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "ts", "flv", "wmv", "mpg", "mpeg")
    private val STREAM_EXT = setOf("m3u8", "mpd")

    // href/src/content/data-src attributes, quoted either way, plus bare magnet links in text.
    private val ATTR = Regex("""(?:href|src|content|data-src|data-url)\s*=\s*(["'])(.*?)\1""", RegexOption.IGNORE_CASE)
    private val BARE_URL = Regex("""https?://[^\s"'<>()]+""", RegexOption.IGNORE_CASE)
    private val MAGNET = Regex("""magnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    fun extract(html: String, pageUrl: String): List<FoundVideo> {
        val candidates = LinkedHashSet<String>()
        ATTR.findAll(html).forEach { candidates += unescape(it.groupValues[2]) }
        // Also links in scripts and JSON, where slashes are often escaped.
        BARE_URL.findAll(unescape(html)).forEach { candidates += it.value }
        MAGNET.findAll(html).forEach { candidates += unescape(it.value) }

        val seen = HashSet<String>()
        return candidates.mapNotNull { raw -> classify(raw.trim(), pageUrl) }
            .filter { seen.add(it.url) }
    }

    /** The video [raw] points to, resolved against [pageUrl], or null if it isn't one. */
    internal fun classify(raw: String, pageUrl: String): FoundVideo? {
        if (raw.startsWith("magnet:", ignoreCase = true)) {
            val name = Regex("[?&]dn=([^&]+)").find(raw)?.groupValues?.get(1)
                ?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }
            return FoundVideo(raw, name ?: "Magnet link", FoundVideo.Kind.MAGNET)
        }
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("data:") || raw.startsWith("javascript:")) return null
        val url = try {
            URI(pageUrl).resolve(raw.replace(" ", "%20")).toURL().toString()
        } catch (_: Exception) {
            return null
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) return null
        val path = runCatching { URI(url).path }.getOrNull().orEmpty()
        val file = path.substringAfterLast('/')
        val ext = file.substringAfterLast('.', "").lowercase()
        val kind = when (ext) {
            in VIDEO_EXT -> FoundVideo.Kind.FILE
            in STREAM_EXT -> FoundVideo.Kind.STREAM
            "torrent" -> FoundVideo.Kind.TORRENT
            else -> return null
        }
        val name = runCatching { URLDecoder.decode(file, "UTF-8") }.getOrDefault(file)
        return FoundVideo(url, name, kind)
    }

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("\\/", "/")

    /**
     * Loads [pageUrl] and lists its videos. A link straight to a video is returned as itself.
     * Blocking network work runs on IO.
     */
    suspend fun scan(pageUrl: String): List<FoundVideo> = withContext(Dispatchers.IO) {
        val url = normalize(pageUrl)
        classify(url, url)?.let { return@withContext listOf(it) }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.instanceFollowRedirects = true
        // Some sites send a bare page to unknown clients.
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) ReelPlay")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("The page returned HTTP $code")
            val type = conn.contentType.orEmpty().lowercase()
            val finalUrl = conn.url.toString()
            if (type.startsWith("video/")) {
                val name = finalUrl.substringAfterLast('/').substringBefore('?').ifEmpty { "Video" }
                return@withContext listOf(FoundVideo(finalUrl, name, FoundVideo.Kind.FILE))
            }
            if (type.contains("bittorrent")) return@withContext listOf(FoundVideo(finalUrl, "Torrent", FoundVideo.Kind.TORRENT))
            val html = conn.inputStream.use { input ->
                // Enough for any real page; stops a huge file being read as text.
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (out.size() < MAX_PAGE) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toString("UTF-8")
            }
            extract(html, finalUrl)
        } finally {
            conn.disconnect()
        }
    }

    fun normalize(input: String): String {
        val s = input.trim()
        return if (s.contains("://") || s.startsWith("magnet:", true)) s else "https://$s"
    }

    private const val MAX_PAGE = 8 * 1024 * 1024
}
