package com.eshwar.reelplay.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamReadinessTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024.0 * 1024

    // A 14 GB, 2 h 20 min film plays at about 1.7 MB/s.
    private val size = 14 * gb
    private val duration = (2 * 60 + 20) * 60_000L

    @Test
    fun fastDownload_needsOnlyAShortCushion() {
        val plan = StreamReadiness.plan(size, duration, contiguousBytes = 0, bytesPerSecond = 10 * mb)
        assertTrue(plan.fastEnough)
        // 20 seconds of video, not gigabytes.
        assertEquals(plan.bitrate * StreamReadiness.MIN_BUFFER_SECONDS, plan.neededBytes.toDouble(), 1.0)
        assertFalse(plan.ready)
        assertTrue(StreamReadiness.plan(size, duration, plan.neededBytes, 10 * mb).ready)
    }

    @Test
    fun slowDownload_buffersEnoughToFinishWithoutStalling() {
        val rate = 1.0 * mb // Slower than the ~1.7 MB/s the film plays at.
        val plan = StreamReadiness.plan(size, duration, contiguousBytes = 0, bytesPerSecond = rate)
        assertFalse(plan.fastEnough)
        // Simulate: start once the plan is met, download sequentially at the discounted rate,
        // play at the bitrate. Playback must never overtake the download.
        val start = plan.neededBytes.toDouble()
        val effective = rate * StreamReadiness.SAFETY
        var t = 0.0
        while (t <= duration / 1000.0) {
            val downloaded = minOf(size.toDouble(), start + effective * t)
            val played = plan.bitrate * t
            assertTrue("stalled at ${t}s", downloaded >= played - 1)
            t += 10
        }
        assertTrue(plan.etaSeconds!! > 0)
    }

    @Test
    fun nothingArriving_hasNoEta_andIsntReady() {
        val plan = StreamReadiness.plan(size, duration, contiguousBytes = gb, bytesPerSecond = 0.0)
        assertFalse(plan.ready)
        assertNull(plan.etaSeconds)
        assertEquals(size, plan.neededBytes)
    }

    @Test
    fun unknownDuration_guessesAFilmLength() {
        val plan = StreamReadiness.plan(size, null, 0, 5 * mb)
        assertTrue(plan.bitrateGuessed)
        assertEquals(size / StreamReadiness.GUESSED_DURATION_SECONDS, plan.bitrate, 1.0)
    }

    @Test
    fun neverAsksForMoreThanTheFile() {
        val small = 50L * 1024 * 1024
        val plan = StreamReadiness.plan(small, 60_000, small, 1.0)
        assertTrue(plan.neededBytes <= small)
        assertTrue(plan.ready)
    }
}
