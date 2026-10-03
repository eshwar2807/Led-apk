package com.eshwar.reelplay.torrent

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.core.net.toUri
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.swig.bdecode_node
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.torrent_flags_t
import org.libtorrent4j.swig.settings_pack
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** One playable file inside a torrent. */
data class TorrentFile(val index: Int, val path: String, val name: String, val size: Long) {
    val isVideo: Boolean get() = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTENSIONS
}

/**
 * A torrent whose metadata we have, before anything is downloaded. [bytes] is the .torrent
 * itself. [trackers] and [webSeeds] are kept separately because libtorrent 2.1 no longer
 * carries them in TorrentInfo: adding a torrent from its TorrentInfo alone silently drops
 * every tracker, leaving only DHT to find peers.
 */
class TorrentMeta(
    val info: TorrentInfo,
    val bytes: ByteArray,
    val trackers: List<String> = emptyList(),
    val webSeeds: List<String> = emptyList(),
) {
    val name: String = info.name()
    val infoHash: String = info.infoHash().toHex()
    val files: List<TorrentFile> = (0 until info.numFiles()).map { i ->
        val fs = info.files()
        TorrentFile(i, fs.filePath(i), fs.fileName(i), fs.fileSize(i))
    }
    val videoFiles: List<TorrentFile> get() = files.filter { it.isVideo }

    companion object {
        /**
         * Parses a .torrent the libtorrent 2.1 way, keeping its trackers and web seeds.
         * [extraTrackers] are added too (e.g. the ones a magnet link listed).
         */
        fun parse(bytes: ByteArray, extraTrackers: List<String> = emptyList()): TorrentMeta {
            val info = try {
                TorrentInfo(bytes)
            } catch (e: Exception) {
                throw IOException("That isn't a valid torrent file", e)
            }
            val buffer = Vectors.bytes2byte_vector(bytes)
            val node = bdecode_node()
            val ec = error_code()
            var trackers = emptyList<String>()
            var seeds = emptyList<String>()
            if (bdecode_node.bdecode(buffer, node, ec) == 0) {
                try {
                    val p = AddTorrentParams(libtorrent.load_torrent_parsed(node))
                    trackers = p.trackers
                    seeds = p.urlSeeds
                } catch (_: Exception) {
                    // Still usable from TorrentInfo alone; DHT finds peers.
                }
            }
            // The decoded node points into [buffer]; keep it alive until parsing is done.
            buffer.size
            return TorrentMeta(info, bytes, (trackers + extraTrackers).distinct(), seeds)
        }

        /** Trackers listed in a magnet link. */
        fun magnetTrackers(magnet: String): List<String> {
            val ec = error_code()
            val p = libtorrent.parse_magnet_uri(magnet, ec)
            return if (ec.value() == 0) AddTorrentParams(p).trackers else emptyList()
        }
    }
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

    /**
     * Tuned for throughput: more peers at once, found faster, each kept busier. A torrent is
     * only as fast as the number of peers sending to you in parallel and how much you ask each
     * of them for at a time; libtorrent's defaults are cautious, desktop-seedbox values.
     */
    fun mobileSettings(): SettingsPack = SettingsPack()
        .connectionsLimit(400)
        .activeDownloads(8)
        .activeSeeds(8)
        .apply {
            setEnableDht(true)
            setEnableLsd(true)
            // Open connections to new peers faster (libtorrent: 30/s), and on starting a
            // torrent connect to this many at once rather than ramping up.
            setInteger(settings_pack.int_types.connection_speed.swigValue(), 80)
            setInteger(settings_pack.int_types.torrent_connect_boost.swigValue(), 80)
            // Keep more block requests outstanding per peer, so fast peers never sit idle
            // waiting for the next request (the usual cap on a single peer's speed).
            setInteger(settings_pack.int_types.max_out_request_queue.swigValue(), 1500)
            setInteger(settings_pack.int_types.max_allowed_in_request_queue.swigValue(), 2000)
            // Remember more candidate peers, and give up on unresponsive ones sooner.
            setInteger(settings_pack.int_types.max_peerlist_size.swigValue(), 6000)
            setInteger(settings_pack.int_types.peer_connect_timeout.swigValue(), 10)
            // Verify pieces on several cores; a 14 GB resume check is hashing-bound.
            setInteger(
                settings_pack.int_types.hashing_threads.swigValue(),
                Runtime.getRuntime().availableProcessors().coerceIn(2, 4),
            )
            // Ask every tracker for peers at once instead of one tier after another.
            setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
            setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
            // Let peers reach us too (router port mapping), over both TCP and uTP.
            setBoolean(settings_pack.bool_types.enable_upnp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_natpmp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_incoming_utp.swigValue(), true)
            setBoolean(settings_pack.bool_types.enable_outgoing_utp.swigValue(), true)
            setBoolean(settings_pack.bool_types.smooth_connects.swigValue(), false)
        }

    /**
     * Big, long-running open trackers. Many torrents and most magnet links list few or dead
     * trackers; announcing to these too finds far more peers. Never added to private torrents,
     * whose trackers must be the only ones that learn about them.
     */
    val PUBLIC_TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
        "udp://open.demonii.com:1337/announce",
        "udp://explodie.org:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce",
        "udp://tracker.tiny-vps.com:6969/announce",
    )

    /** [magnet] plus any of [PUBLIC_TRACKERS] it doesn't list, so its details arrive sooner. */
    fun withPublicTrackers(magnet: String): String {
        val existing = Regex("[?&]tr=([^&]*)").findAll(magnet)
            .map { java.net.URLDecoder.decode(it.groupValues[1], "UTF-8") }.toSet()
        val extra = PUBLIC_TRACKERS.filter { it !in existing }
        if (extra.isEmpty()) return magnet
        return magnet + extra.joinToString("") { "&tr=" + java.net.URLEncoder.encode(it, "UTF-8") }
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
                s.fetchMagnet(withPublicTrackers(source), MAGNET_TIMEOUT_S, dir)
                    ?: throw IOException("No peers sent this torrent's details within ${MAGNET_TIMEOUT_S}s")
            }
            source.startsWith("http://", true) || source.startsWith("https://", true) -> download(source)
            else -> context.contentResolver.openInputStream(source.toUri())?.use { it.readBytes() }
                ?: throw IOException("Couldn't open the torrent file")
        }
        val magnetTrackers = if (source.startsWith("magnet:", ignoreCase = true)) {
            TorrentMeta.magnetTrackers(source)
        } else {
            emptyList()
        }
        return TorrentMeta.parse(bytes, magnetTrackers)
    }

    /** The torrent's own trackers plus, unless it's private, [PUBLIC_TRACKERS]. */
    fun trackersFor(meta: TorrentMeta): List<String> =
        if (meta.info.isPrivate) meta.trackers else (meta.trackers + PUBLIC_TRACKERS).distinct()

    /**
     * Adds a torrent with everything libtorrent needs to find peers: trackers, web seeds and
     * file priorities. Replaces SessionManager.download, which in libtorrent 2.1 passes only
     * the TorrentInfo and so loses the trackers. Synchronous: returns the handle.
     */
    fun addTorrent(
        s: SessionManager,
        meta: TorrentMeta,
        saveDir: File,
        priorities: Array<Priority>,
        flags: torrent_flags_t = torrent_flags_t(),
        peers: List<TcpEndpoint> = emptyList(),
    ): TorrentHandle {
        s.find(meta.info.infoHash())?.takeIf { it.isValid }?.let { existing ->
            existing.prioritizeFiles(priorities)
            return existing
        }
        val p = AddTorrentParams()
        p.setTorrentInfo(meta.info)
        p.savePath = saveDir.absolutePath
        p.filePriorities(priorities)
        p.trackers = trackersFor(meta)
        if (meta.webSeeds.isNotEmpty()) p.urlSeeds = meta.webSeeds
        if (peers.isNotEmpty()) p.peers(peers)
        p.flags = p.flags.or_(flags)
        val ec = error_code()
        val handle = s.swig().add_torrent(p.swig(), ec)
        if (ec.value() != 0) throw IOException("Couldn't add the torrent: ${ec.message()}")
        return TorrentHandle(handle)
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
        val handle = addTorrent(s, meta, dir, priorities, TorrentFlags.SEQUENTIAL_DOWNLOAD)
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
