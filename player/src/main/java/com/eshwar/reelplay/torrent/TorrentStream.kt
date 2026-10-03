package com.eshwar.reelplay.torrent

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.io.RandomAccessFile

/** Live numbers for the player overlay. */
data class TorrentStats(
    val downloadBytesPerSec: Int,
    val peers: Int,
    val seeds: Int,
    /** Fraction of the chosen file downloaded, 0..1. */
    val progress: Float,
)

/**
 * One file of a torrent being streamed. The torrent downloads in order; when the player reads
 * somewhere it doesn't have yet, the pieces there get deadlines so libtorrent fetches them first.
 */
class TorrentStream(
    val handle: TorrentHandle,
    val meta: TorrentMeta,
    val file: TorrentFile,
    val path: File,
    /** False when the torrent belongs to a download and must outlive this stream. */
    val ownsTorrent: Boolean,
) {
    private val pieceLength = meta.info.pieceLength().toLong()
    private val fileOffset = meta.info.files().fileOffset(file.index)
    val firstPiece = pieceAt(0)
    val lastPiece = pieceAt((file.size - 1).coerceAtLeast(0))

    @Volatile private var closed = false
    private var lastWanted = -1
    /** Highest piece known to be present with everything before it present too. */
    @Volatile private var contiguousUpTo = firstPiece - 1
    /** Recent download speed, for setting deadlines libtorrent can actually meet. */
    @Volatile private var rateEstimate = DEFAULT_RATE

    val size: Long get() = file.size

    fun pieceAt(filePosition: Long): Int = ((fileOffset + filePosition) / pieceLength).toInt()

    /** Bytes of this file from [filePosition] to the end of its piece. */
    fun bytesLeftInPiece(filePosition: Long): Long {
        val absolute = fileOffset + filePosition
        return pieceLength - absolute % pieceLength
    }

    /** Pieces covering [bytes], kept within [min]..[max] whatever the piece size (16 KiB … 64 MiB). */
    private fun piecesFor(bytes: Long, min: Int, max: Int): Int =
        ((bytes + pieceLength - 1) / pieceLength).toInt().coerceIn(min, max)

    fun has(piece: Int): Boolean = try {
        handle.havePiece(piece)
    } catch (_: Exception) {
        false
    }

    /**
     * Containers keep their index at the start (and MP4s and MKVs often at the end), so fetch
     * both ends first. Without this, players stall reading the header before playback can start.
     */
    fun primeEnds() {
        val head = minOf(firstPiece + piecesFor(HEAD_BYTES, 2, 16) - 1, lastPiece)
        for (p in firstPiece..head) setDeadline(p, deadlineFor(p - firstPiece + 1))
        val tail = piecesFor(TAIL_BYTES, 1, 4)
        for (p in maxOf(head + 1, lastPiece - tail + 1)..lastPiece) setDeadline(p, deadlineFor(2))
    }

    /** Whether the header and the end of the file are here, so the container can be probed. */
    fun endsReady(): Boolean {
        val head = minOf(firstPiece + piecesFor(PROBE_BYTES, 1, 4) - 1, lastPiece)
        return (firstPiece..head).all(::has) && has(lastPiece)
    }

    /** Bytes from the start of the file that are all on disk, with no gaps. */
    fun contiguousBytes(): Long {
        var p = contiguousUpTo + 1
        while (p <= lastPiece && has(p)) p++
        contiguousUpTo = p - 1
        if (contiguousUpTo < firstPiece) return 0
        return ((contiguousUpTo + 1) * pieceLength - fileOffset).coerceAtMost(file.size)
    }

    /** Blocks until the piece holding [filePosition] is downloaded and verified. */
    fun awaitPosition(filePosition: Long) {
        val piece = pieceAt(filePosition)
        prefetchFrom(piece)
        var polls = 0
        while (!has(piece)) {
            if (closed) throw InterruptedIOException("Torrent stream closed")
            if (!handle.isValid) throw IOException("The torrent was removed")
            // Every couple of seconds, make sure the torrent hasn't failed (disk full, file
            // error), and say so instead of buffering forever.
            if (++polls % 20 == 0) error()?.let { throw IOException(it) }
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Interrupted while waiting for piece $piece")
            }
        }
    }

    /**
     * Reads up to [length] bytes of the file at [filePosition] from [file], waiting for them to
     * download first. Stops at the end of the piece, since the next one may not be here yet.
     * Returns -1 at the end of the file.
     */
    fun read(file: RandomAccessFile, filePosition: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (filePosition >= size) return -1
        awaitPosition(filePosition)
        val want = minOf(length.toLong(), size - filePosition, bytesLeftInPiece(filePosition)).toInt()
        file.seek(filePosition)
        return file.read(buffer, offset, want)
    }

    /** Tells libtorrent what the player will want next. Cheap to call on every read. */
    @Synchronized
    private fun prefetchFrom(piece: Int) {
        if (piece == lastWanted) return
        val window = piecesFor(READ_AHEAD_BYTES, 3, 40)
        // A jump (seek) makes the old deadlines stale; clear them so the new spot wins.
        if (lastWanted < 0 || piece < lastWanted || piece > lastWanted + window) {
            try {
                handle.clearPieceDeadlines()
            } catch (_: Exception) {
            }
        }
        lastWanted = piece
        val end = minOf(piece + window - 1, lastPiece)
        for (p in piece..end) {
            if (!has(p)) setDeadline(p, deadlineFor(p - piece + 1))
        }
    }

    /**
     * Milliseconds to fetch [pieces] pieces at the recent rate. Deadlines that can't be met make
     * libtorrent request the same blocks from several peers at once, which wastes the bandwidth
     * a big torrent needs, so they're kept realistic.
     */
    private fun deadlineFor(pieces: Int): Int {
        val ms = pieces * pieceLength * 1000.0 / rateEstimate
        return ms.toInt().coerceIn(MIN_DEADLINE_MS, MAX_DEADLINE_MS)
    }

    private fun setDeadline(piece: Int, ms: Int) {
        try {
            handle.piecePriority(piece, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(piece, ms)
        } catch (_: Exception) {
        }
    }

    /** libtorrent's error for this torrent, e.g. "No space left on device", or null. */
    fun error(): String? = try {
        handle.status().errorCode().takeIf { it.isError }?.message
    } catch (_: Exception) {
        null
    }

    fun stats(): TorrentStats? = try {
        val s = handle.status()
        val done = handle.fileProgress().getOrNull(file.index) ?: 0L
        val rate = s.downloadPayloadRate()
        if (rate > 0) rateEstimate = rateEstimate * 0.7 + rate * 0.3
        TorrentStats(
            downloadBytesPerSec = rate,
            peers = s.numPeers(),
            seeds = s.numSeeds(),
            progress = if (file.size > 0) (done.toFloat() / file.size).coerceIn(0f, 1f) else 0f,
        )
    } catch (_: Exception) {
        null
    }

    fun close() {
        closed = true
        // A download carries on at its own pace once nobody is watching.
        if (!ownsTorrent) {
            try {
                handle.clearPieceDeadlines()
            } catch (_: Exception) {
            }
        }
    }

    private companion object {
        const val MIB = 1024L * 1024
        const val HEAD_BYTES = 16 * MIB
        const val TAIL_BYTES = 4 * MIB
        const val PROBE_BYTES = 4 * MIB
        const val READ_AHEAD_BYTES = 64 * MIB
        const val DEFAULT_RATE = 1.0 * MIB
        const val MIN_DEADLINE_MS = 1_000
        const val MAX_DEADLINE_MS = 10 * 60_000
        const val POLL_MS = 100L
    }
}
