package com.eshwar.reelplay.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RateMeterTest {
    @Test
    fun measuresAndSmoothsSpeed() {
        val m = RateMeter(smoothing = 0.5f)
        assertNull(m.sample(0, 0))
        assertEquals(1_000_000L, m.sample(1_000_000, 1_000))
        // A second at 3 MB/s moves the estimate halfway there.
        assertEquals(2_000_000L, m.sample(4_000_000, 2_000))
        // A stall pulls it down rather than to zero at once, and never below zero.
        val after = m.sample(4_000_000, 3_000)!!
        assertTrue(after in 1..1_999_999)
        assertEquals(after, m.sample(3_000_000, 3_000)) // Same instant / going backwards: ignored.
    }
}
