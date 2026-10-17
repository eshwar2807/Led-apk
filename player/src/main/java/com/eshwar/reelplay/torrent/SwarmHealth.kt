package com.eshwar.reelplay.torrent

import com.eshwar.reelplay.BuildConfig
import com.eshwar.reelplay.ui.formatSize
import org.libtorrent4j.TorrentHandle
import java.util.Locale

/**
 * Why a torrent is as fast (or slow) as it is. A torrent can only download as fast as the
 * people sharing it upload, so most slowness is the swarm or the network, not the app; this
 * says which, in plain words, from libtorrent's own numbers.
 */
data class SwarmHealth(
    val downloadRate: Int,
    val connectedPeers: Int,
    val connectedSeeds: Int,
    /** Seeders/leechers in the whole swarm according to trackers; -1 when no tracker said. */
    val swarmSeeds: Int,
    val swarmPeers: Int,
    val connectCandidates: Int,
    val availability: Float,
    val trackersWorking: Int,
    val trackersFailing: Int,
    val trackersTotal: Int,
    val dhtNodes: Long,
    val firewalled: Boolean,
) {
    enum class Severity { OK, WARN, BAD }

    data class Verdict(val severity: Severity, val title: String, val detail: String)

    val verdict: Verdict
        get() = when {
            trackersWorking == 0 && dhtNodes < 20 -> Verdict(
                Severity.BAD, "Can't reach the torrent network",
                "No tracker answers and DHT has found only $dhtNodes nodes. This network may block " +
                    "BitTorrent (many offices, schools and some mobile carriers do). Try another Wi-Fi " +
                    "or a VPN.",
            )
            availability in 0f..0.999f && connectedSeeds == 0 && swarmSeeds <= 0 -> Verdict(
                Severity.BAD, "Nobody has the whole file",
                "No seeders are online and the connected peers together hold only " +
                    "${String.format(Locale.US, "%.0f", availability * 100)}% of it. It will stall until a seeder appears.",
            )
            swarmSeeds in 0..4 -> Verdict(
                Severity.WARN, "Very few seeders ($swarmSeeds)",
                "Speed is capped by how fast those few people upload, not by the app or your connection. " +
                    "A torrent with more seeders will be much faster.",
            )
            connectedPeers < 5 && connectCandidates > 0 -> Verdict(
                Severity.WARN, "Still connecting",
                "$connectCandidates more peers are known but not connected yet. Give it a minute.",
            )
            firewalled -> Verdict(
                Severity.WARN, "Incoming connections are blocked",
                "Your router or carrier doesn't let peers connect to you, so only peers that accept " +
                    "connections can be used. Usual on mobile data; on home Wi-Fi, enabling UPnP on the " +
                    "router helps.",
            )
            else -> Verdict(
                Severity.OK, "Healthy",
                "$connectedPeers peers connected ($connectedSeeds seeders). Speed is now set by how fast " +
                    "they upload and by your connection.",
            )
        }

    /** Plain-text summary to paste into a bug report. */
    fun report(name: String): String = buildString {
        appendLine("All Media Player ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) torrent diagnostics")
        appendLine("Torrent: $name")
        appendLine("Verdict: ${verdict.title}")
        appendLine("Speed: ${formatSize(downloadRate.toLong())}/s")
        appendLine("Connected: $connectedPeers peers, $connectedSeeds seeders")
        appendLine("Swarm (trackers): ${if (swarmSeeds < 0) "?" else swarmSeeds} seeders, ${if (swarmPeers < 0) "?" else swarmPeers} leechers")
        appendLine("Waiting to connect: $connectCandidates")
        appendLine("Availability: ${String.format(Locale.US, "%.2f", availability)} copies")
        appendLine("Trackers: $trackersWorking working, $trackersFailing failing, $trackersTotal total")
        appendLine("DHT nodes: $dhtNodes")
        append("Incoming connections: ${if (firewalled) "blocked" else "open"}")
    }

    companion object {
        /** Reads the numbers for [handle]. Blocking but quick; call off the main thread. */
        fun of(handle: TorrentHandle): SwarmHealth? = try {
            val s = handle.status()
            var working = 0
            var failing = 0
            val trackers = handle.trackers()
            for (t in trackers) {
                val endpoints = t.endpoints()
                val hashes = endpoints.flatMap { listOf(it.infohashV1(), it.infohashV2()) }
                when {
                    hashes.any { it.isWorking } -> working++
                    hashes.isNotEmpty() && hashes.all { it.fails() > 0 } -> failing++
                }
            }
            SwarmHealth(
                downloadRate = s.downloadPayloadRate(),
                connectedPeers = s.numPeers(),
                connectedSeeds = s.numSeeds(),
                swarmSeeds = s.numComplete(),
                swarmPeers = s.numIncomplete(),
                connectCandidates = s.connectCandidates(),
                availability = s.distributedCopies(),
                trackersWorking = working,
                trackersFailing = failing,
                trackersTotal = trackers.size,
                dhtNodes = TorrentEngine.dhtNodes(),
                firewalled = TorrentEngine.isFirewalled(),
            )
        } catch (_: Exception) {
            null
        }
    }
}
