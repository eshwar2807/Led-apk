package com.eshwar.reelplay.torrent

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.RandomAccessFile

/**
 * Lets ExoPlayer read a file that is still downloading. Reads block until the piece under them
 * arrives (ExoPlayer shows buffering meanwhile), and never cross into a piece we don't have.
 */
@OptIn(UnstableApi::class)
class TorrentDataSource(private val stream: TorrentStream) : BaseDataSource(/* isNetwork= */ true) {

    private var uri: Uri? = null
    private var file: RandomAccessFile? = null
    private var position = 0L
    private var remaining = 0L
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        if (dataSpec.position > stream.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) stream.size - position else dataSpec.length
        opened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        // The file only appears on disk once its first piece lands.
        stream.awaitPosition(position)
        val f = file ?: RandomAccessFile(stream.path, "r").also { file = it }
        val read = stream.read(f, position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read <= 0) return C.RESULT_END_OF_INPUT
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    /** Routes `torrent://` URIs here and everything else to the normal data sources. */
    class Factory(context: Context) : DataSource.Factory {
        private val fallback = DefaultDataSource.Factory(context)

        override fun createDataSource(): DataSource = Routing(fallback.createDataSource())
    }

    private class Routing(private val fallback: DataSource) : DataSource by fallback {
        private var active: DataSource = fallback

        override fun open(dataSpec: DataSpec): Long {
            val stream = TorrentEngine.stream(dataSpec.uri)
            active = if (stream != null) TorrentDataSource(stream) else fallback
            return active.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = active.read(buffer, offset, length)

        override fun getUri(): Uri? = active.uri

        override fun getResponseHeaders(): Map<String, List<String>> = active.responseHeaders

        override fun close() = active.close()
    }
}
