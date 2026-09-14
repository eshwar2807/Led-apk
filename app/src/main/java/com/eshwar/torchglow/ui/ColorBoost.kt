package com.eshwar.torchglow.ui

import android.content.Context
import android.provider.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Making coloured light bright.
 *
 * A fully saturated colour only drives one or two of each pixel's subpixels, so
 * even at maximum screen brightness pure blue puts out a small fraction of what
 * white does — and the same holds for a diffused RGB LED. The fix is not more
 * brightness (there is none left) but mixing white back into the colour: it lights
 * the other subpixels while the hue survives.
 *
 * [boost] is that mix, 0f for the pure hue and 1f for plain white.
 */
fun boosted(color: Color, boost: Float): Color {
    val amount = boost.coerceIn(0f, 1f)
    if (amount <= 0f) return color
    return Color(
        red = color.red + (1f - color.red) * amount,
        green = color.green + (1f - color.green) * amount,
        blue = color.blue + (1f - color.blue) * amount,
        alpha = 1f,
    )
}

/**
 * The [boosted] mix that brings [color] up to [targetLuminance] — used by the
 * "Max output" button and to even out hues, since an equal mix leaves blue far
 * dimmer than yellow.
 *
 * Luminance is non-linear in the mix, so this solves numerically rather than
 * pretending there is a closed form.
 */
fun boostToReachLuminance(color: Color, targetLuminance: Float): Float {
    if (color.luminance() >= targetLuminance) return 0f
    var low = 0f
    var high = 1f
    repeat(20) {
        val mid = (low + high) / 2f
        if (boosted(color, mid).luminance() < targetLuminance) low = mid else high = mid
    }
    return high
}

/** Luminance of the brightest sensible coloured output — just short of white. */
const val MAX_OUTPUT_LUMINANCE = 0.85f

/**
 * System settings that quietly cap how bright the screen can go. Worth naming,
 * because with either of these on the lamp is dimmer than the phone is capable of
 * and no in-app control can make up for it.
 */
fun dimmingWarnings(context: Context): List<String> {
    val resolver = context.contentResolver
    val warnings = mutableListOf<String>()
    fun isOn(key: String) = runCatching { Settings.Secure.getInt(resolver, key, 0) }.getOrDefault(0) == 1

    if (isOn("reduce_bright_colors_activated")) {
        warnings += "Extra dim is on — turn it off for full output"
    }
    if (isOn("night_display_activated")) {
        warnings += "Night Light is on — it warms and dims the screen"
    }
    return warnings
}
