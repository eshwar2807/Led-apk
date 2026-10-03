package com.eshwar.reelplay.torrent

import kotlin.math.max
import kotlin.math.min

/**
 * When can a torrent stream start and then play to the end without stopping to buffer?
 *
 * The file downloads front to back at rate R while playback consumes it at bitrate B. Playback
 * reaches byte x at time x/B; the download, starting with P bytes in hand, reaches it at
 * (x − P)/R. Playback never catches up if P ≥ (B − R)·t for every t up to the duration D, i.e.
 * P ≥ (B − R)·D = S − R·D. If R ≥ B only a short cushion is needed. R is discounted because
 * swarm speed wobbles, and the result is never less than [MIN_BUFFER_SECONDS] of video.
 */
object StreamReadiness {

    data class Plan(
        /** Contiguous bytes from the start that must be on disk before playing. */
        val neededBytes: Long,
        val ready: Boolean,
        /** Seconds until [neededBytes] at the current rate; null when nothing is arriving. */
        val etaSeconds: Long?,
        /** Bytes per second the video plays at. */
        val bitrate: Double,
        /** True when the bitrate comes from a guessed duration rather than the file itself. */
        val bitrateGuessed: Boolean,
        /** Download outpaces playback even after the safety discount. */
        val fastEnough: Boolean,
    )

    const val MIN_BUFFER_SECONDS = 20.0
    const val SAFETY = 0.75
    /** Used until the real duration is known: most big single-video torrents are films. */
    const val GUESSED_DURATION_SECONDS = 2 * 60 * 60.0

    fun plan(fileSize: Long, durationMs: Long?, contiguousBytes: Long, bytesPerSecond: Double): Plan {
        val guessed = durationMs == null || durationMs <= 0
        val duration = if (guessed) GUESSED_DURATION_SECONDS else durationMs / 1000.0
        val bitrate = fileSize / duration
        val rate = max(0.0, bytesPerSecond) * SAFETY
        val cushion = bitrate * MIN_BUFFER_SECONDS
        val toOutrun = fileSize - rate * duration
        val needed = min(fileSize.toDouble(), max(cushion, toOutrun)).toLong()
        val ready = contiguousBytes >= needed
        val missing = (needed - contiguousBytes).coerceAtLeast(0)
        val eta = when {
            ready -> 0L
            bytesPerSecond > 0 -> (missing / bytesPerSecond).toLong()
            else -> null
        }
        return Plan(needed, ready, eta, bitrate, guessed, rate >= bitrate)
    }
}
