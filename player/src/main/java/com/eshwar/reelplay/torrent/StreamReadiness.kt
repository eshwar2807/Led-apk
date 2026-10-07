package com.eshwar.reelplay.torrent

import kotlin.math.max
import kotlin.math.min

/**
 * When should a torrent stream (re)start playing?
 *
 * The file downloads front to back at rate R while playback consumes it at bitrate B. With P
 * bytes buffered ahead of the playhead, playback catches the download after P / (B − R)
 * seconds. Waiting until it never catches up (P ≥ (B − R)·D for the whole duration D) means
 * waiting for most of the file whenever the swarm is slower than the video, and if R < B the
 * total waiting can't be reduced, only moved. So the plan aims for a *smooth stretch*: enough
 * that the next [HORIZON_SECONDS] (or the rest of the video, if shorter) play without
 * stopping. If the swarm is still slower by then, the player stops once and buffers the next
 * stretch, rather than stuttering every few seconds. The same rule decides both the first start
 * and every restart after a stall, from wherever the playhead is.
 *
 * R is discounted a little because swarm speed wobbles, and the buffer is never less than
 * [MIN_BUFFER_SECONDS] of video.
 */
object StreamReadiness {

    data class Plan(
        /** File position (bytes) the contiguous download must reach before playing. */
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
        /** Seconds of smooth playback the plan buys: the horizon, or the rest of the video. */
        val smoothSeconds: Double,
        /** Seconds that would play without stopping if started right now (null: never stops). */
        val smoothIfStartedNow: Double?,
    )

    const val MIN_BUFFER_SECONDS = 20.0
    const val SAFETY = 0.85
    /** How long each smooth stretch should last before another stop, if the swarm is slow. */
    const val HORIZON_SECONDS = 10 * 60.0
    /** Used until the real duration is known: most big single-video torrents are films. */
    const val GUESSED_DURATION_SECONDS = 2 * 60 * 60.0

    /**
     * [contiguousBytes] is how far the file is on disk without gaps, counting from
     * [positionBytes] (where playback is, 0 before it starts).
     */
    fun plan(
        fileSize: Long,
        durationMs: Long?,
        contiguousBytes: Long,
        bytesPerSecond: Double,
        positionBytes: Long = 0,
        horizonSeconds: Double = HORIZON_SECONDS,
    ): Plan {
        val guessed = durationMs == null || durationMs <= 0
        val duration = if (guessed) GUESSED_DURATION_SECONDS else durationMs!! / 1000.0
        val bitrate = fileSize / duration
        val rate = max(0.0, bytesPerSecond) * SAFETY
        val position = positionBytes.coerceIn(0, fileSize)
        val remainingBytes = fileSize - position
        val remainingSeconds = remainingBytes / bitrate
        val stretch = min(remainingSeconds, horizonSeconds)

        val cushion = bitrate * MIN_BUFFER_SECONDS
        val deficit = max(0.0, bitrate - rate) * stretch
        val ahead = min(remainingBytes.toDouble(), max(cushion, deficit)).toLong()
        val needed = position + ahead
        val have = contiguousBytes.coerceAtLeast(position)
        val ready = have >= needed
        val missing = (needed - have).coerceAtLeast(0)
        val eta = when {
            ready -> 0L
            bytesPerSecond > 0 -> (missing / bytesPerSecond).toLong()
            else -> null
        }
        val haveAhead = (have - position).toDouble()
        val smoothNow = if (rate >= bitrate) null else min(remainingSeconds, haveAhead / (bitrate - rate))
        return Plan(needed, ready, eta, bitrate, guessed, rate >= bitrate, stretch, smoothNow)
    }
}
