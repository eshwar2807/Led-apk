package com.eshwar.reelplay.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportQualityTest {

    @Test
    fun sameSizeExport_isAtLeastTheOriginalsBitrate() {
        // A 4K phone clip at 48 Mbps, exported at 4K.
        val b = ExportQuality.targetBitrate(48_000_000, 3840, 2160, 3840, 2160, 30f)
        assertTrue("$b", b >= 48_000_000)
    }

    @Test
    fun smallerExport_scalesWithPixels_butKeepsAQualityFloor() {
        // 4K at 48 Mbps exported at 1080p: a quarter of the pixels.
        val b = ExportQuality.targetBitrate(48_000_000, 3840, 2160, 1920, 1080, 30f)
        assertEquals(48_000_000 / 4 * 1.15, b.toDouble(), 2.0)
        // A low-bitrate source still gets enough for its size and frame rate.
        val floor = ExportQuality.targetBitrate(1_000_000, 1920, 1080, 1920, 1080, 60f)
        assertEquals((1920L * 1080 * 60 * 0.15).toDouble(), floor.toDouble(), 1.0)
    }

    @Test
    fun unknownSource_usesTheFloor_withinLimits() {
        val b = ExportQuality.targetBitrate(0, 0, 0, 1280, 720, 0f)
        assertEquals((1280L * 720 * 30 * 0.15).toDouble(), b.toDouble(), 1.0)
        val tiny = ExportQuality.targetBitrate(0, 0, 0, 320, 240, 30f)
        assertEquals(2_000_000, tiny)
        val huge = ExportQuality.targetBitrate(400_000_000, 7680, 4320, 7680, 4320, 120f)
        assertEquals(120_000_000, huge)
    }
}
