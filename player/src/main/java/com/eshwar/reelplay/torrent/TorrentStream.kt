package com.eshwar.reelplay.torrent

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.File
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
) {
    private val pieceLength = meta.info.pieceLength().toLong()
    private val fileOffset = meta.info.files().fileOffset(file.index)
    val firstPiece = pieceAt(0)
    val lastPiece = pieceAt((file.size - 1).coerceAtLeast(0))

    @Volatile private var closed = false
    private var lastWanted = -1

    val size: Long get() = file.size

    fun pieceAt(filePosition: Long): Int = ((fileOffset + filePosition) / pieceLength).toInt()

    /** Bytes of this file from [filePosition] to the end of its piece. */
    fun bytesLeftInPiece(filePosition: Long): Long {
        val absolute = fileOffset + filePosition
        return pieceLength - absolute % pieceLength
    }

    fun has(piece: Int): Boolean = try {
        handle.havePiece(piece)
    } catch (_: Exception) {
        false
    }

    /**
     * Containers keep their index at the start (and MP4s often at the end), so fetch both ends
     * first. Without this, players stall reading the header before playback can start.
     */
    fun primeEnds() {
        val head = minOf(firstPiece + HEAD_PIECES - 1, lastPiece)
        for (p in firstPiece..head) setDeadline(p, (p - firstPiece) * 100)
        for (p in maxOf(head + 1, lastPiece - 1)..lastPiece) setDeadline(p, 200)
    }

    /** Pieces needed before handing over to the player: a little of the start, plus the tail. */
    fun readyToPlay(): Boolean {
        val head = minOf(firstPiece + START_PIECES - 1, lastPiece)
        return (firstPiece..head).all(::has) && has(lastPiece)
    }

    /** Fraction of the pre-buffer [readyToPlay] waits for. */
    fun startProgress(): Float {
        val head = minOf(firstPiece + START_PIECES - 1, lastPiece)
        val needed = (firstPiece..head).toList() + lastPiece
        return needed.distinct().count(::has).toFloat() / needed.distinct().size
    }

    /** Blocks until the piece holding [filePosition] is downloaded and verified. */
    fun awaitPosition(filePosition: Long) {
        val piece = pieceAt(filePosition)
        if (has(piece)) {
            prefetchFrom(piece)
            return
        }
        prefetchFrom(piece)
        while (!has(piece)) {
            if (closed) throw InterruptedIOException("Torrent stream closed")
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
        // A jump (seek) makes the old deadlines stale; clear them so the new spot wins.
        if (lastWanted < 0 || piece < lastWanted || piece > lastWanted + READ_AHEAD) {
            try {
                handle.clearPieceDeadlines()
            } catch (_: Exception) {
            }
        }
        lastWanted = piece
        val end = minOf(piece + READ_AHEAD, lastPiece)
        for (p in piece..end) {
            if (!has(p)) setDeadline(p, (p - piece) * 150)
        }
    }

    private fun setDeadline(piece: Int, ms: Int) {
        try {
            handle.piecePriority(piece, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(piece, ms)
        } catch (_: Exception) {
        }
    }

    fun stats(): TorrentStats? = try {
        val s = handle.status()
        val done = handle.fileProgress().getOrNull(file.index) ?: 0L
        TorrentStats(
            downloadBytesPerSec = s.downloadPayloadRate(),
            peers = s.numPeers(),
            seeds = s.numSeeds(),
            progress = if (file.size > 0) (done.toFloat() / file.size).coerceIn(0f, 1f) else 0f,
        )
    } catch (_: Exception) {
        null
    }

    fun close() {
        closed = true
    }

    private companion object {
        const val HEAD_PIECES = 8
        const val START_PIECES = 3
        const val READ_AHEAD = 12
        const val POLL_MS = 100L
    }
}
