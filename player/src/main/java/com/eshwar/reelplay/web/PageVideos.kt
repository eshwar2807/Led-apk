package com.eshwar.reelplay.web

import kotlinx.coroutines.Dispatchers
import org.jsoup.Jsoup
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder

/**
 * A video link found on a web page. [label] is what the page calls it (link text, a title
 * attribute, the page title for its only video); [poster] is a preview image the page gives.
 */
data class FoundVideo(
    val url: String,
    val name: String,
    val kind: Kind,
    val label: String? = null,
    val poster: String? = null,
) {
    /** What to show as its title: the page's own words when there are any, else the file name. */
    val title: String get() = label ?: name

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
    private val BARE_URL = Regex("""https?://[^\s"'<>()]+""", RegexOption.IGNORE_CASE)
    private val MAGNET = Regex("""magnet:\?[^\s"'<>]+""", RegexOption.IGNORE_CASE)

    private val LINK_ATTRS = listOf("href", "src", "data-src", "data-url", "data-video", "data-file", "content")

    fun extract(html: String, pageUrl: String): List<FoundVideo> {
        val doc = Jsoup.parse(html, pageUrl)
        val pageTitle = clean(doc.selectFirst("meta[property=og:title]")?.attr("content")) ?: clean(doc.title())
        val pageImage = doc.selectFirst("meta[property=og:image], meta[name=twitter:image]")?.absUrl("content")?.ifEmpty { null }

        val found = LinkedHashMap<String, FoundVideo>()
        fun add(raw: String, label: String?, poster: String?) {
            val v = classify(raw.trim(), pageUrl) ?: return
            val old = found[v.url]
            // The same video linked twice: keep the first, filling in what it was missing.
            found[v.url] = if (old == null) v.copy(label = label, poster = poster)
            else old.copy(label = old.label ?: label, poster = old.poster ?: poster)
        }

        for (el in doc.select(LINK_ATTRS.joinToString(",") { "[$it]" })) {
            val video = el.closest("video")
            val label = when {
                el.tagName() == "a" -> clean(el.text()) ?: clean(el.attr("title")) ?: clean(el.attr("aria-label"))
                    ?: clean(el.selectFirst("img")?.attr("alt"))
                video != null -> clean(video.attr("title")) ?: clean(video.attr("aria-label"))
                el.tagName() == "meta" -> pageTitle
                else -> clean(el.attr("title"))
            }
            val poster = video?.absUrl("poster")?.ifEmpty { null }
                ?: el.selectFirst("img")?.absUrl("src")?.ifEmpty { null }
                ?: if (el.tagName() == "meta") pageImage else null
            for (attr in LINK_ATTRS) {
                val value = el.attr(attr)
                if (value.isNotEmpty()) add(value, label, poster)
            }
        }
        // Also links in scripts and JSON, where slashes are often escaped.
        val text = unescape(html)
        BARE_URL.findAll(text).forEach { add(it.value, null, null) }
        MAGNET.findAll(text).forEach { add(it.value, null, null) }

        val list = found.values.toList()
        // A page with one video is about that video: use the page's title and image for it.
        return if (list.size == 1) {
            listOf(list[0].copy(label = list[0].label ?: pageTitle, poster = list[0].poster ?: pageImage))
        } else {
            list
        }
    }

    /** Readable text or null: whitespace collapsed, no bare "Download"/"Play"-style words. */
    private fun clean(s: String?): String? {
        val t = s?.replace(Regex("\\s+"), " ")?.trim()?.take(140) ?: return null
        return t.takeIf { it.length >= 3 && it.lowercase() !in GENERIC }
    }

    private val GENERIC = setOf("download", "play", "watch", "here", "click here", "link", "video", "mp4", "hd")

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
