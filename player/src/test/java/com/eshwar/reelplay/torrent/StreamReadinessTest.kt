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
        assertNull(plan.smoothIfStartedNow)
    }

    @Test
    fun slowDownload_buysASmoothStretch_notTheWholeFilm() {
        val rate = 1.0 * mb // Slower than the ~1.7 MB/s the film plays at.
        val plan = StreamReadiness.plan(size, duration, contiguousBytes = 0, bytesPerSecond = rate)
        assertFalse(plan.fastEnough)
        assertEquals(StreamReadiness.HORIZON_SECONDS, plan.smoothSeconds, 0.001)
        // Far less than waiting out the whole film (which would be ~6 GB here).
        val wholeFilm = size - rate * StreamReadiness.SAFETY * duration / 1000
        assertTrue(plan.neededBytes < wholeFilm / 5)
        // Simulate: start once the plan is met, download at the discounted rate, play at the
        // bitrate. Playback must not overtake the download within the stretch.
        val start = plan.neededBytes.toDouble()
        val effective = rate * StreamReadiness.SAFETY
        var t = 0.0
        while (t <= plan.smoothSeconds) {
            val downloaded = minOf(size.toDouble(), start + effective * t)
            assertTrue("stalled at ${t}s", downloaded >= plan.bitrate * t - 1)
            t += 5
        }
        assertTrue(plan.etaSeconds!! > 0)
    }

    @Test
    fun afterAStall_plansTheNextStretchFromThePlayhead() {
        val rate = 1.0 * mb
        val at = 5 * gb
        val plan = StreamReadiness.plan(size, duration, contiguousBytes = at, bytesPerSecond = rate, positionBytes = at)
        val first = StreamReadiness.plan(size, duration, 0, rate)
        // Same amount ahead of the playhead as at the start.
        assertEquals(first.neededBytes.toDouble(), (plan.neededBytes - at).toDouble(), 1.0)
        assertFalse(plan.ready)
        // Near the end, the stretch is just what's left, and the need shrinks with it.
        val nearEnd = size - (plan.bitrate * 60).toLong()
        val end = StreamReadiness.plan(size, duration, nearEnd, rate, positionBytes = nearEnd)
        assertTrue(end.neededBytes <= size)
        assertTrue(end.neededBytes - nearEnd < plan.neededBytes - at)
        assertTrue(end.smoothSeconds <= 60.5)
    }

    @Test
    fun nothingArriving_hasNoEta_andIsntReady() {
        val plan = StreamReadiness.plan(size, duration, contiguousBytes = 100L * 1024 * 1024, bytesPerSecond = 0.0)
        assertFalse(plan.ready)
        assertNull(plan.etaSeconds)
        // A whole stretch of video, since nothing is arriving to keep up.
        assertEquals(plan.bitrate * StreamReadiness.HORIZON_SECONDS, plan.neededBytes.toDouble(), 1.0)
    }

    @Test
    fun smoothIfStartedNow_growsWithTheBuffer() {
        val rate = 1.0 * mb
        val small = StreamReadiness.plan(size, duration, 200L * 1024 * 1024, rate).smoothIfStartedNow!!
        val big = StreamReadiness.plan(size, duration, 800L * 1024 * 1024, rate).smoothIfStartedNow!!
        assertTrue(big > small * 3.9)
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
