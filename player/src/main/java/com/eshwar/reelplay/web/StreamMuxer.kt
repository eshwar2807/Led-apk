package com.eshwar.reelplay.web

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Turns downloaded stream data (MPEG-TS or fragmented MP4, plus a separate audio file for
 * streams that keep their sound apart) into one ordinary MP4. Samples are copied, not
 * re-encoded, so it takes seconds even for a film.
 */
object StreamMuxer {

    private class Source(val extractor: MediaExtractor, val track: Int, val muxerTrack: Int)

    /**
     * Writes [video] (and [audio], if given) into [out]. Throws if Android can't read the
     * streams or the MP4 container can't hold their codecs.
     */
    fun toMp4(video: File, audio: File?, out: File) {
        val sources = ArrayList<Source>()
        val extractors = ArrayList<MediaExtractor>()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        try {
            fun add(file: File, wantVideo: Boolean, wantAudio: Boolean) {
                val ex = MediaExtractor().apply { setDataSource(file.path) }
                extractors += ex
                for (i in 0 until ex.trackCount) {
                    val format = ex.getTrackFormat(i)
                    val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                    val isVideo = mime.startsWith("video/")
                    val isAudio = mime.startsWith("audio/")
                    if ((isVideo && wantVideo && sources.none { it.extractor === ex && isVideoTrack(it) }) ||
                        (isAudio && wantAudio && sources.none { isAudioTrack(it) })
                    ) {
                        ex.selectTrack(i)
                        sources += Source(ex, i, muxer.addTrack(format))
                    }
                }
            }
            add(video, wantVideo = true, wantAudio = audio == null)
            if (audio != null) add(audio, wantVideo = false, wantAudio = true)
            if (sources.none(::isVideoTrack)) throw IOException("No video track the phone can read")

            // Streams rarely start at time 0. Shift each file to start at 0: tracks inside one file
            // keep their relative timing, and a separate audio file starts where its video does
            // (both renditions begin at the same segment boundary).
            val base = extractors.associateWith { it.sampleTime.coerceAtLeast(0) }
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocate(8 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            // Interleave by time across files (video and a separate audio track), so the MP4
            // plays smoothly and the muxer never has to hold one whole track in memory.
            while (true) {
                val ex = extractors.filter { it.sampleTrackIndex >= 0 }.minByOrNull { it.sampleTime - base.getValue(it) } ?: break
                val source = sources.firstOrNull { it.extractor === ex && it.track == ex.sampleTrackIndex }
                if (source != null) {
                    val size = ex.readSampleData(buffer, 0)
                    if (size >= 0) {
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, size, (ex.sampleTime - base.getValue(ex)).coerceAtLeast(0), flags)
                        muxer.writeSampleData(source.muxerTrack, buffer, info)
                    }
                }
                ex.advance()
            }
        } finally {
            try {
                if (started) muxer.stop()
            } finally {
                muxer.release()
                extractors.forEach { it.release() }
            }
        }
    }

    private fun mimeOf(s: Source) = s.extractor.getTrackFormat(s.track).getString(MediaFormat.KEY_MIME).orEmpty()
    private fun isVideoTrack(s: Source) = mimeOf(s).startsWith("video/")
    private fun isAudioTrack(s: Source) = mimeOf(s).startsWith("audio/")
}
