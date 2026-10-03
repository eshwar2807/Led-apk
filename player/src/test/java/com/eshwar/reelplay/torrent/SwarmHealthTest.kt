package com.eshwar.reelplay.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwarmHealthTest {

    private val healthy = SwarmHealth(
        downloadRate = 4_000_000, connectedPeers = 40, connectedSeeds = 25, swarmSeeds = 300, swarmPeers = 80,
        connectCandidates = 10, availability = 30f, trackersWorking = 6, trackersFailing = 2, trackersTotal = 9,
        dhtNodes = 300, firewalled = false,
    )

    @Test fun healthySwarm() = assertEquals("Healthy", healthy.verdict.title)

    @Test fun blockedNetwork() {
        val v = healthy.copy(trackersWorking = 0, dhtNodes = 3, connectedPeers = 0).verdict
        assertEquals(SwarmHealth.Severity.BAD, v.severity)
        assertTrue(v.title.contains("Can't reach"))
    }

    @Test fun nobodyHasTheWholeFile() {
        val v = healthy.copy(connectedSeeds = 0, swarmSeeds = 0, availability = 0.62f).verdict
        assertEquals("Nobody has the whole file", v.title)
        assertTrue(v.detail.contains("62%"))
    }

    @Test fun fewSeeders() = assertTrue(healthy.copy(swarmSeeds = 2).verdict.title.startsWith("Very few seeders"))

    @Test fun stillConnecting() = assertEquals("Still connecting", healthy.copy(connectedPeers = 1).verdict.title)

    @Test fun firewalled() = assertTrue(healthy.copy(firewalled = true).verdict.title.contains("Incoming"))

    @Test fun reportHasTheNumbers() {
        val r = healthy.report("Big.Movie.mkv")
        assertTrue(r.contains("Big.Movie.mkv") && r.contains("40 peers") && r.contains("6 working"))
    }
}
