package com.eshwar.reelplay.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.source.MediaSource
import com.eshwar.reelplay.torrent.StreamReadiness
import com.eshwar.reelplay.torrent.TorrentStream

/**
 * ExoPlayer's usual buffering rules, plus one for torrents: don't (re)start playback until the
 * download can keep up for a while (see [StreamReadiness]). After a stall that means a full
 * smooth stretch, so a slow swarm causes one longer pause instead of a stutter every few
 * seconds; after a seek, a shorter one so jumping around stays quick.
 */
@OptIn(UnstableApi::class)
class TorrentLoadControl(private val delegate: LoadControl) : LoadControl {

    /** The torrent being played and its length; set from the main thread, read on the player's. */
    class Target(val stream: TorrentStream, val durationMs: Long)

    @Volatile var target: Target? = null

    /** The last plan, for the buffering readout. Null when not buffering a torrent. */
    @Volatile var lastPlan: StreamReadiness.Plan? = null
        private set

    @Volatile private var lastCheckMs = 0L
    @Volatile private var lastAnswer = false

    override fun shouldStartPlayback(parameters: LoadControl.Parameters): Boolean {
        if (!delegate.shouldStartPlayback(parameters)) return false
        val t = target ?: return true.also { lastPlan = null }
        // Planning scans pieces; ExoPlayer asks many times a second, so answer from a cache.
        val now = System.currentTimeMillis()
        if (now - lastCheckMs < CHECK_INTERVAL_MS) return lastAnswer
        lastCheckMs = now
        val stream = t.stream
        val bitrate = stream.size * 1000.0 / t.durationMs.coerceAtLeast(1)
        val position = (parameters.playbackPositionUs / 1_000_000.0 * bitrate).toLong().coerceIn(0, stream.size)
        val plan = StreamReadiness.plan(
            stream.size, t.durationMs,
            contiguousBytes = stream.contiguousFrom(position),
            bytesPerSecond = stream.recentRate,
            positionBytes = position,
            horizonSeconds = if (parameters.rebuffering) StreamReadiness.HORIZON_SECONDS else AFTER_SEEK_SECONDS,
        )
        lastPlan = plan
        lastAnswer = plan.ready
        if (!plan.ready) {
            // Aim the download at the stretch being waited for.
            stream.bufferAheadBytes = plan.neededBytes - position
            stream.focusAt(position)
        }
        if (plan.ready) lastPlan = null
        return lastAnswer
    }

    // Everything else is ExoPlayer's default behaviour.
    override fun onPrepared(playerId: PlayerId) = delegate.onPrepared(playerId)
    override fun onTracksSelected(parameters: LoadControl.Parameters, trackGroups: TrackGroupArray, trackSelections: Array<out ExoTrackSelection?>) =
        delegate.onTracksSelected(parameters, trackGroups, trackSelections)
    override fun onStopped(playerId: PlayerId) = delegate.onStopped(playerId)
    override fun onReleased(playerId: PlayerId) = delegate.onReleased(playerId)
    override fun getAllocator(playerId: PlayerId): Allocator = delegate.getAllocator(playerId)
    override fun getBackBufferDurationUs(playerId: PlayerId): Long = delegate.getBackBufferDurationUs(playerId)
    override fun retainBackBufferFromKeyframe(playerId: PlayerId): Boolean = delegate.retainBackBufferFromKeyframe(playerId)
    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean = delegate.shouldContinueLoading(parameters)
    override fun shouldContinuePreloading(playerId: PlayerId, timeline: Timeline, mediaPeriodId: MediaSource.MediaPeriodId, bufferedDurationUs: Long): Boolean =
        delegate.shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs)

    companion object {
        /** After a seek: two minutes smooth, then the usual stretches if the swarm is slow. */
        const val AFTER_SEEK_SECONDS = 2 * 60.0
        private const val CHECK_INTERVAL_MS = 500L
    }
}
