package com.eshwar.reelplay.editor

import android.media.MediaCodecList
import androidx.media3.common.MimeTypes
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Export settings that keep the original's quality: its resolution by default, a bitrate at
 * least as high as the original's (scaled to the output size), and its own codec when the
 * phone can encode it.
 */
data class ExportQuality(val videoBitrate: Int, val videoMime: String) {

    val codecLabel: String get() = when (videoMime) {
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        else -> "H.264"
    }

    companion object {
        /** Never below this, whatever the source says (some phones under-report). */
        private const val MIN_BITRATE = 2_000_000L
        private const val MAX_BITRATE = 120_000_000L
        /** Bits per pixel per frame that keeps H.264 visually lossless for phone footage. */
        private const val BITS_PER_PIXEL = 0.15

        /**
         * The bitrate to encode at: the source's, scaled by how many pixels the output has
         * compared with it, plus 15% because a re-encode loses a little at equal bitrate; and
         * never less than a quality floor for the output size and frame rate.
         */
        fun targetBitrate(sourceBitrate: Int, sourceW: Int, sourceH: Int, outW: Int, outH: Int, fps: Float): Int {
            val outPixels = outW.toLong() * outH
            val frameRate = if (fps in 1f..240f) fps else 30f
            val floor = (outPixels * frameRate * BITS_PER_PIXEL).roundToLong()
            val scaled = if (sourceBitrate > 0 && sourceW > 0 && sourceH > 0) {
                (sourceBitrate * (outPixels.toDouble() / (sourceW.toLong() * sourceH)) * 1.15).roundToLong()
            } else {
                0L
            }
            return min(MAX_BITRATE, max(MIN_BITRATE, max(floor, scaled))).toInt()
        }

        fun forProject(project: Project, shortSide: Int): ExportQuality {
            val (outW, outH) = project.outputSize(shortSide)
            // The richest video clip sets the bar.
            val reference = project.clips.filterNot { it.isImage }.maxByOrNull { it.info.width.toLong() * it.info.height }
            val bitrate = if (reference == null) {
                targetBitrate(0, 0, 0, outW, outH, 30f)
            } else {
                targetBitrate(reference.info.bitrate, reference.info.width, reference.info.height, outW, outH, reference.info.frameRate)
            }
            val sourceMime = reference?.info?.videoMime
            val mime = if (sourceMime in setOf(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264) && canEncode(sourceMime!!)) {
                sourceMime
            } else {
                MimeTypes.VIDEO_H264
            }
            return ExportQuality(bitrate, mime)
        }

        private fun canEncode(mime: String): Boolean = try {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { it.isEncoder && mime in it.supportedTypes.map(String::lowercase) }
        } catch (_: Exception) {
            false
        }
    }
}
