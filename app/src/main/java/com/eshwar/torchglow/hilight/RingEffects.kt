package com.eshwar.torchglow.hilight

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.math.sin

/** How the wheel colour is painted across the LEDs of the ring. */
enum class RingMode(val label: String) {
    SOLID("Solid"),
    BREATHE("Breathe"),
    CHASE("Chase"),
    SPECTRUM("Spectrum"),
}

/**
 * Colours for every LED of the ring at animation time [phase] (turns, 0f..1f
 * repeating). [count] LEDs sit evenly around the circle in ordinal order.
 *
 * Effects are computed here and pushed frame by frame rather than handed to the
 * HAL as a MultiLightEffect, so the same code drives any device whose ring the
 * app can reach, however many LEDs it has.
 */
fun ringColors(
    mode: RingMode,
    base: Color,
    count: Int,
    phase: Float,
): IntArray {
    if (count <= 0) return IntArray(0)
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(base.toArgb(), hsv)
    val (hue, saturation, value) = Triple(hsv[0], hsv[1], hsv[2])

    return IntArray(count) { index ->
        val position = index.toFloat() / count
        when (mode) {
            RingMode.SOLID -> base.toArgb()

            RingMode.BREATHE -> {
                // Sine in and out, never fully dark so the ring stays visible.
                val level = 0.15f + 0.85f * (0.5f + 0.5f * sin(TWO_PI * phase))
                Color.hsv(hue, saturation, value * level).toArgb()
            }

            RingMode.CHASE -> {
                // One bright head with a fading tail running around the circle.
                val distance = wrappedDistance(position, phase)
                val level = (1f - distance * count / TAIL_LEDS).coerceIn(0f, 1f)
                Color.hsv(hue, saturation, value * (0.05f + 0.95f * level * level)).toArgb()
            }

            RingMode.SPECTRUM -> {
                // Full hue circle smeared around the ring, rotating.
                val shifted = (hue + 360f * (position + phase)) % 360f
                Color.hsv(shifted, saturation.coerceAtLeast(0.6f), value).toArgb()
            }
        }
    }
}

/** Shortest distance between two points on a unit circle. */
private fun wrappedDistance(a: Float, b: Float): Float {
    val raw = abs(a - b) % 1f
    return minOf(raw, 1f - raw)
}

/** How many LEDs the chase tail spans. */
private const val TAIL_LEDS = 3f
private const val TWO_PI = (2.0 * Math.PI).toFloat()
