package com.eshwar.reelplay.web

import java.net.URI

/**
 * Just enough of HLS (RFC 8216) to download a stream: master playlists (the quality list)
 * and media playlists (the segments). Pure, so it's unit tested.
 */
object Hls {

    /** One quality of a stream. [audioUrl] is set when its sound comes from a separate playlist. */
    data class Variant(
        val url: String,
        val bandwidth: Long,
        val width: Int,
        val height: Int,
        val codecs: String?,
        val audioUrl: String?,
    ) {
        val label: String
            get() = buildString {
                // The short side, so portrait video still reads as 1080p and not 1920p.
                val p = if (width > 0 && height > 0) minOf(width, height) else height
                append(if (p > 0) "${p}p" else "Auto")
                if (bandwidth > 0) append(" · ").append(String.format(java.util.Locale.ROOT, "%.1f", bandwidth / 1e6)).append(" Mbps")
            }
    }

    data class Key(val method: String, val url: String?, val iv: ByteArray?, val keyFormat: String?)

    data class Segment(
        val url: String,
        val durationSec: Double,
        /** Byte range within [url] (EXT-X-BYTERANGE), as offset and length. */
        val range: LongRange?,
        val key: Key?,
        /** Media sequence number; the default AES-128 IV. */
        val sequence: Long,
    )

    data class MediaPlaylist(
        val segments: List<Segment>,
        /** EXT-X-MAP: the init segment of fragmented-MP4 streams. */
        val initUrl: String?,
        val initRange: LongRange?,
        /** False for live streams, which have no end to download to. */
        val ended: Boolean,
    ) {
        val durationSec: Double get() = segments.sumOf { it.durationSec }
        val encryptedWith: Set<String> get() = segments.mapNotNull { it.key?.method }.filter { it != "NONE" }.toSet()
    }

    fun isMaster(text: String) = text.lineSequence().any { it.startsWith("#EXT-X-STREAM-INF") }

    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        val lines = text.lineSequence().map { it.trim() }.toList()
        // Separate audio playlists, by group: prefer the default one.
        val audio = HashMap<String, String>()
        for (line in lines.filter { it.startsWith("#EXT-X-MEDIA:") }) {
            val a = attributes(line.substringAfter(':'))
            if (a["TYPE"] != "AUDIO") continue
            val group = a["GROUP-ID"] ?: continue
            val uri = a["URI"] ?: continue
            if (group !in audio || a["DEFAULT"] == "YES") audio[group] = resolve(baseUrl, uri)
        }
        val variants = ArrayList<Variant>()
        for ((i, line) in lines.withIndex()) {
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
            val uri = lines.drop(i + 1).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: continue
            val a = attributes(line.substringAfter(':'))
            val (w, h) = a["RESOLUTION"]?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }
                ?.let { it[0] to it[1] } ?: (0 to 0)
            variants += Variant(
                url = resolve(baseUrl, uri),
                bandwidth = a["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: a["BANDWIDTH"]?.toLongOrNull() ?: 0,
                width = w,
                height = h,
                codecs = a["CODECS"],
                audioUrl = a["AUDIO"]?.let { audio[it] },
            )
        }
        // Best first; the same stream listed twice (e.g. for different CDNs) only once.
        return variants.distinctBy { it.url }.sortedWith(compareByDescending<Variant> { it.height }.thenByDescending { it.bandwidth })
    }

    /**
     * The qualities worth offering for download: one per resolution (the highest bitrate),
     * H.264 over HEVC/AV1 when a stream has both, since every phone plays H.264.
     */
    fun downloadChoices(variants: List<Variant>): List<Variant> {
        val avc = variants.filter { it.codecs?.contains("avc1") == true }
        val usable = if (avc.isNotEmpty()) avc + variants.filter { it.codecs == null } else variants
        return usable.groupBy { v -> if (v.width > 0 && v.height > 0) minOf(v.width, v.height) else v.height }
            .map { (_, same) -> same.maxBy { it.bandwidth } }
            .sortedWith(compareByDescending<Variant> { minOf(it.width, it.height).takeIf { h -> h > 0 } ?: it.height }.thenByDescending { it.bandwidth })
    }

    fun parseMedia(text: String, baseUrl: String): MediaPlaylist {
        val segments = ArrayList<Segment>()
        var sequence = 0L
        var duration = 0.0
        var range: LongRange? = null
        var lastRangeEnd = 0L
        var key: Key? = null
        var initUrl: String? = null
        var initRange: LongRange? = null
        var ended = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> sequence = line.substringAfter(':').trim().toLongOrNull() ?: 0
                line.startsWith("#EXTINF:") -> duration = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull() ?: 0.0
                line.startsWith("#EXT-X-BYTERANGE:") -> {
                    range = byteRange(line.substringAfter(':'), lastRangeEnd)
                }
                line.startsWith("#EXT-X-KEY:") -> {
                    val a = attributes(line.substringAfter(':'))
                    val method = a["METHOD"] ?: "NONE"
                    key = if (method == "NONE") null else Key(
                        method = method,
                        url = a["URI"]?.let { resolve(baseUrl, it) },
                        iv = a["IV"]?.let(::hex),
                        keyFormat = a["KEYFORMAT"],
                    )
                }
                line.startsWith("#EXT-X-MAP:") -> {
                    val a = attributes(line.substringAfter(':'))
                    initUrl = a["URI"]?.let { resolve(baseUrl, it) }
                    initRange = a["BYTERANGE"]?.let { byteRange(it, 0) }
                }
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-PLAYLIST-TYPE:") -> if (line.endsWith("VOD")) ended = true
                line.startsWith("#") -> Unit
                else -> {
                    segments += Segment(resolve(baseUrl, line), duration, range, key, sequence)
                    range?.let { lastRangeEnd = it.last + 1 }
                    sequence++
                    duration = 0.0
                    range = null
                }
            }
        }
        return MediaPlaylist(segments, initUrl, initRange, ended)
    }

    /** "length[@offset]"; without an offset the range follows the previous one. */
    private fun byteRange(spec: String, follows: Long): LongRange? {
        val length = spec.substringBefore('@').trim().toLongOrNull() ?: return null
        val offset = spec.substringAfter('@', "").trim().toLongOrNull() ?: follows
        return offset until offset + length
    }

    /** Attribute lists: KEY=value,KEY="quoted, with commas". */
    internal fun attributes(list: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("""([A-Z0-9-]+)=("[^"]*"|[^,]*)""").findAll(list).forEach {
            out[it.groupValues[1]] = it.groupValues[2].trim().removeSurrounding("\"")
        }
        return out
    }

    private fun hex(s: String): ByteArray? {
        val h = s.removePrefix("0x").removePrefix("0X")
        if (h.length % 2 != 0) return null
        return ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    internal fun resolve(base: String, ref: String): String = try {
        URI(base).resolve(ref.trim().replace(" ", "%20")).toString()
    } catch (_: Exception) {
        ref
    }
}
