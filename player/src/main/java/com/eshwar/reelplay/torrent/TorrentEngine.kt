package com.eshwar.reelplay.torrent

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.core.net.toUri
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** One playable file inside a torrent. */
data class TorrentFile(val index: Int, val path: String, val name: String, val size: Long) {
    val isVideo: Boolean get() = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
}

/** A torrent whose metadata we have, before anything is downloaded. [bytes] is the .torrent itself. */
class TorrentMeta(val info: TorrentInfo, val bytes: ByteArray) {
    val name: String = info.name()
    val infoHash: String = info.infoHash().toHex()
    val files: List<TorrentFile> = (0 until info.numFiles()).map { i ->
        val fs = info.files()
        TorrentFile(i, fs.filePath(i), fs.fileName(i), fs.fileSize(i))
    }
    val videoFiles: List<TorrentFile> get() = files.filter { it.isVideo }
}

val VIDEO_EXTENSIONS = setOf(
    "mp4", "m4v", "mkv", "webm", "mov", "avi", "ts", "m2ts", "mts", "3gp", "flv", "wmv", "mpg", "mpeg", "ogv",
)

/**
 * The app's single BitTorrent session, shared by streaming and [TorrentDownloads]. A torrent
 * added just to stream one file is removed, data and all, when the player lets go of it; a
 * stream borrowed from a download leaves the download alone.
 */
object TorrentEngine {

    const val SCHEME = "torrent"

    private var session: SessionManager? = null
    private lateinit var saveRoot: File
    private val streams = ConcurrentHashMap<String, TorrentStream>()

    /** App-private storage for torrent data: `torrents/` for streams, `downloads/` for downloads. */
    fun storageRoot(context: Context): File =
        context.applicationContext.getExternalFilesDir(null) ?: context.applicationContext.filesDir

    @Synchronized
    fun session(context: Context): SessionManager {
        session?.let { return it }
        saveRoot = File(storageRoot(context), "torrents")
        // Anything left here is from a session that was killed mid-stream.
        saveRoot.deleteRecursively()
        saveRoot.mkdirs()
        return SessionManager(false).also {
            it.start(sessionParams(mobileSettings()))
            session = it
        }
    }

    fun mobileSettings(): SettingsPack = SettingsPack()
        .connectionsLimit(200)
        .activeDownloads(8)
        .activeSeeds(8)
        .apply {
            setEnableDht(true)
            setEnableLsd(true)
        }

    /** Shared with the JVM tests, so they exercise the same disk backend the phone uses. */
    fun sessionParams(settings: SettingsPack): SessionParams = SessionParams(settings).apply {
        // libtorrent 2 memory-maps every file by default. On a phone that maps gigabytes of a
        // big torrent into the app, so the low-memory killer takes it (and 32-bit phones run out
        // of address space), and a full disk becomes a SIGBUS crash instead of an error.
        // Plain pread/pwrite has none of those problems.
        setPosixDiskIO()
    }

    /** Free space where torrent data is written. */
    fun freeBytes(context: Context): Long = try {
        StatFs(storageRoot(context).path).availableBytes
    } catch (_: Exception) {
        Long.MAX_VALUE
    }

    /** Fails early, with sizes, instead of filling the disk halfway through. */
    fun requireSpace(context: Context, bytes: Long) {
        val free = freeBytes(context)
        if (free < bytes + SPACE_MARGIN) {
            throw IOException(
                "Not enough storage: this needs ${humanSize(bytes)}, but only ${humanSize(free)} is free",
            )
        }
    }

    private fun humanSize(bytes: Long): String = com.eshwar.reelplay.ui.formatSize(bytes)

    /** Reads a torrent from a `.torrent` file/link or a magnet URI. Blocking; call off the main thread. */
    fun resolve(context: Context, source: String): TorrentMeta {
        val s = session(context)
        val bytes: ByteArray = when {
            source.startsWith("magnet:", ignoreCase = true) -> {
                val dir = File(saveRoot, "meta").apply { mkdirs() }
                s.fetchMagnet(source, MAGNET_TIMEOUT_S, dir)
                    ?: throw IOException("No peers sent this torrent's details within ${MAGNET_TIMEOUT_S}s")
            }
            source.startsWith("http://", true) || source.startsWith("https://", true) -> download(source)
            else -> context.contentResolver.openInputStream(source.toUri())?.use { it.readBytes() }
                ?: throw IOException("Couldn't open the torrent file")
        }
        val info = try {
            TorrentInfo(bytes)
        } catch (e: Exception) {
            throw IOException("That isn't a valid torrent file", e)
        }
        return TorrentMeta(info, bytes)
    }

    private fun download(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        return try {
            if (conn.responseCode !in 200..299) throw IOException("Server returned ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /** Starts fetching [file] (and nothing else in the torrent), front to back. */
    fun start(context: Context, meta: TorrentMeta, file: TorrentFile): TorrentStream {
        val key = key(meta.infoHash, file.index)
        streams[key]?.let { return it }
        // Already in Downloads: stream from that copy rather than fight over the torrent.
        if (TorrentDownloads.contains(meta.infoHash)) return TorrentDownloads.streamFile(context, meta.infoHash, file.index)
        // A stream keeps the whole file on disk while it plays.
        requireSpace(context, file.size)
        val s = session(context)
        val priorities = Array(meta.files.size) { if (it == file.index) Priority.DEFAULT else Priority.IGNORE }
        val dir = File(saveRoot, meta.infoHash).apply { mkdirs() }
        s.download(meta.info, dir, null, priorities, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val handle = awaitHandle(s, meta)
        // Out of libtorrent's queue, so a stream never waits behind downloads.
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
        handle.resume()
        return attach(TorrentStream(handle, meta, file, File(dir, file.path), ownsTorrent = true))
    }

    /** Registers [stream] so the player can open it by [uriFor]. */
    fun attach(stream: TorrentStream): TorrentStream {
        val key = key(stream.meta.infoHash, stream.file.index)
        streams.putIfAbsent(key, stream)?.let { return it }
        stream.primeEnds()
        return stream
    }

    fun awaitHandle(s: SessionManager, meta: TorrentMeta): TorrentHandle {
        // Adding is asynchronous inside libtorrent; the handle appears a moment later.
        repeat(100) {
            s.find(meta.info.infoHash())?.takeIf { it.isValid }?.let { return it }
            Thread.sleep(50)
        }
        throw IOException("The torrent engine didn't accept this torrent")
    }

    fun stream(uri: Uri): TorrentStream? {
        if (uri.scheme != SCHEME) return null
        return streams[key(uri.host ?: return null, uri.lastPathSegment?.toIntOrNull() ?: return null)]
    }

    fun isStreaming(infoHash: String): Boolean = streams.values.any { it.meta.infoHash == infoHash }

    fun uriFor(stream: TorrentStream): Uri =
        Uri.Builder().scheme(SCHEME).authority(stream.meta.infoHash).appendPath(stream.file.index.toString()).build()

    /** Stops the torrent behind [uri] and deletes what it downloaded, unless it belongs to a download. */
    fun close(uri: Uri) {
        val stream = stream(uri) ?: return
        streams.remove(key(stream.meta.infoHash, stream.file.index))
        stream.close()
        if (stream.ownsTorrent && streams.values.none { it.meta.infoHash == stream.meta.infoHash }) {
            try {
                session?.remove(stream.handle, SessionHandle.DELETE_FILES)
            } catch (_: Exception) {
            }
        }
    }

    private fun key(hash: String, index: Int) = "$hash/$index"

    private const val MAGNET_TIMEOUT_S = 90
    private const val SPACE_MARGIN = 200L * 1024 * 1024
}
