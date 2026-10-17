package com.eshwar.reelplay.web

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Fetches stream pieces. The real one adds the page as Referer, which many video hosts require. */
fun interface Fetcher {
    fun get(url: String, range: LongRange?): ByteArray
}

class HttpFetcher(private val referer: String?, private val timeoutMs: Int = 20_000) : Fetcher {
    override fun get(url: String, range: LongRange?): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        referer?.let { conn.setRequestProperty("Referer", it) }
        range?.let { conn.setRequestProperty("Range", "bytes=${it.first}-${it.last}") }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for ${url.substringBefore('?').takeLast(60)}")
            val bytes = conn.inputStream.use { it.readBytes() }
            // A server that ignores Range sends the whole file: cut out the part we asked for.
            if (range != null && code == 200 && bytes.size.toLong() > range.last) {
                return bytes.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
            }
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    fun text(url: String): String = String(get(url, null), Charsets.UTF_8)

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126 Mobile Safari/537.36"
    }
}

class UnsupportedStreamException(message: String) : IOException(message)

/**
 * Downloads an HLS media playlist into one file: the init segment (fragmented MP4 streams)
 * and then every segment in order, decrypting AES-128 ones. Several segments are fetched at
 * once, each retried a few times. Progress is checkpointed to [stateFile] after every piece,
 * so an interrupted download carries on where it stopped.
 */
class HlsDownloader(private val fetcher: Fetcher, private val parallel: Int = 4, private val retries: Int = 4) {

    data class Progress(val donePieces: Int, val totalPieces: Int, val bytes: Long)

    private class Piece(val url: String, val range: LongRange?, val key: Hls.Key?, val sequence: Long)

    suspend fun download(playlist: Hls.MediaPlaylist, out: File, stateFile: File, onProgress: (Progress) -> Unit) {
        check(playlist)
        val pieces = buildList {
            playlist.initUrl?.let { add(Piece(it, playlist.initRange, null, -1)) }
            playlist.segments.forEach { add(Piece(it.url, it.range, it.key, it.sequence)) }
        }
        // Resume: keep what the checkpoint vouches for, drop anything written after it.
        var done = 0
        var bytes = 0L
        if (out.exists() && stateFile.exists()) {
            val parts = stateFile.readText().trim().split(' ')
            val savedDone = parts.getOrNull(0)?.toIntOrNull()
            val savedBytes = parts.getOrNull(1)?.toLongOrNull()
            if (savedDone != null && savedBytes != null && savedDone <= pieces.size && savedBytes <= out.length()) {
                done = savedDone
                bytes = savedBytes
            }
        }
        val keys = HashMap<String, ByteArray>()
        RandomAccessFile(out, "rw").use { file ->
            file.setLength(bytes)
            file.seek(bytes)
            onProgress(Progress(done, pieces.size, bytes))
            coroutineScope {
                val window = ArrayDeque<Deferred<ByteArray>>()
                var next = done
                fun launchNext() {
                    val piece = pieces[next++]
                    window.addLast(async(Dispatchers.IO) { fetchPiece(piece, keys) })
                }
                while (done < pieces.size) {
                    while (window.size < parallel && next < pieces.size) launchNext()
                    val data = window.removeFirst().await()
                    file.write(data)
                    done++
                    bytes += data.size
                    stateFile.writeText("$done $bytes")
                    onProgress(Progress(done, pieces.size, bytes))
                }
            }
        }
    }

    private fun check(playlist: Hls.MediaPlaylist) {
        if (!playlist.ended) throw UnsupportedStreamException("This is a live stream; only finished videos can be downloaded")
        if (playlist.segments.isEmpty()) throw UnsupportedStreamException("The stream has no video segments")
        val methods = playlist.encryptedWith
        if (methods.any { it != "AES-128" } ||
            playlist.segments.any { s -> s.key?.keyFormat.let { it != null && it != "identity" } }
        ) {
            throw UnsupportedStreamException("This video is copy-protected (DRM), so it can't be downloaded")
        }
    }

    private suspend fun fetchPiece(piece: Piece, keys: HashMap<String, ByteArray>): ByteArray =
        decrypt(piece, keys).let { if (isPackedAudio(piece.url)) stripId3(it) else it }

    private suspend fun decrypt(piece: Piece, keys: HashMap<String, ByteArray>): ByteArray {
        val raw = withRetries { fetcher.get(piece.url, piece.range) }
        val key = piece.key ?: return raw
        val keyUrl = key.url ?: throw IOException("Encrypted segment without a key")
        val secret = synchronized(keys) { keys[keyUrl] } ?: withRetries { fetcher.get(keyUrl, null) }.also {
            if (it.size != 16) throw IOException("Bad AES key (${it.size} bytes)")
            synchronized(keys) { keys[keyUrl] = it }
        }
        // Without an explicit IV, AES-128 HLS uses the segment's sequence number.
        val iv = key.iv ?: ByteBuffer.allocate(16).putLong(8, piece.sequence).array()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(secret, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(raw)
    }

    companion object {
        private val PACKED_AUDIO = setOf("aac", "mp3", "ac3", "ec3")

        internal fun isPackedAudio(url: String) =
            url.substringBefore('?').substringAfterLast('.', "").lowercase() in PACKED_AUDIO

        /**
         * Packed-audio segments (.aac and friends) each start with an ID3 tag carrying their
         * timestamp. Joined together, those tags would sit mid-stream and trip up the extractor.
         */
        internal fun stripId3(data: ByteArray): ByteArray {
            var at = 0
            while (data.size - at >= 10 && data[at] == 'I'.code.toByte() && data[at + 1] == 'D'.code.toByte() && data[at + 2] == '3'.code.toByte()) {
                val size = (data[at + 6].toInt() and 0x7f shl 21) or (data[at + 7].toInt() and 0x7f shl 14) or
                    (data[at + 8].toInt() and 0x7f shl 7) or (data[at + 9].toInt() and 0x7f)
                val footer = if (data[at + 5].toInt() and 0x10 != 0) 10 else 0
                at += 10 + size + footer
            }
            return if (at == 0) data else data.copyOfRange(at.coerceAtMost(data.size), data.size)
        }
    }

    private suspend fun <T> withRetries(block: () -> T): T {
        var wait = 1000L
        repeat(retries - 1) {
            try {
                return block()
            } catch (e: UnsupportedStreamException) {
                throw e
            } catch (_: IOException) {
                delay(wait)
                wait *= 2
            }
        }
        return block()
    }
}
