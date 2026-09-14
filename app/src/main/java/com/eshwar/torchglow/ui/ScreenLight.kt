package com.eshwar.torchglow.ui

import android.content.pm.ActivityInfo
import com.eshwar.torchglow.BuildConfig
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen coloured lamp.
 *
 * Two things make this brighter than a plain coloured Activity:
 *
 *  - The window takes the screen brightness override to maximum, so the lamp does
 *    not sit at whatever the system slider happened to be on.
 *  - Where the display supports it, the window asks for an HDR colour mode and the
 *    panel's full HDR headroom, and the colour is painted in extended sRGB with
 *    components above 1.0. SDR white is the *reference* white, not the panel's
 *    limit: HDR content is allowed past it, which is the only way a screen goes
 *    brighter than "full white" ([hdrGain] is how much further, typically 2-5x).
 */
@Composable
fun ScreenLight(
    color: Color,
    pureColor: Color,
    onExit: () -> Unit,
) {
    val view = LocalView.current
    val activity = LocalActivity.current
    var hdrGain by remember { mutableFloatStateOf(1f) }
    var overrideApplied by remember { mutableStateOf(false) }
    var diagnostics by remember { mutableStateOf("") }

    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val previousColorMode = window?.colorMode
        val previousHeadroom =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                window?.desiredHdrHeadroom
            } else {
                null
            }

        window?.applyBrightnessOverride(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        overrideApplied =
            window?.attributes?.screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL

        // Ask for everything the panel will give above SDR white.
        // The view's display is null until it is attached, which is exactly when this
        // effect runs, so prefer the activity's.
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.display ?: view.display
        } else {
            view.display
        }
        var hdrListener: java.util.function.Consumer<android.view.Display>? = null
        // getHighestHdrSdrRatio() is API 36; the headroom request itself is 35, but
        // asking for less than the panel's maximum would defeat the point.
        if (window != null && display != null &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA && display.isHdr
        ) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
            window.desiredHdrHeadroom = display.highestHdrSdrRatio
            hdrGain = display.hdrSdrRatio.coerceAtLeast(1f)

            if (display.isHdrSdrRatioAvailable) {
                // The panel ramps its headroom rather than switching instantly, so
                // follow it instead of sampling once and under-driving the colour.
                val listener = java.util.function.Consumer<android.view.Display> { updated ->
                    hdrGain = updated.hdrSdrRatio.coerceAtLeast(1f)
                }
                hdrListener = listener
                display.registerHdrSdrRatioChangedListener({ it.run() }, listener)
            }
        }

        // Say which stage failed, rather than leaving a dull lamp unexplained.
        diagnostics = buildString {
            append("v").append(BuildConfig.VERSION_NAME).append(" ")
            append("bright=")
            append(
                if (overrideApplied) {
                    "max"
                } else {
                    "FAILED(${window?.attributes?.screenBrightness})"
                },
            )
            append(" sdk=").append(Build.VERSION.SDK_INT)
            append(" display=").append(if (display == null) "null" else "ok")
            if (display != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                append(" isHdr=").append(display.isHdr)
                append(" max=").append("%.2f".format(display.highestHdrSdrRatio))
                append(" now=").append("%.2f".format(display.hdrSdrRatio))
            }
            append(" mode=").append(window?.colorMode)
        }

        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                hdrListener?.let { display?.unregisterHdrSdrRatioChangedListener(it) }
            }
            window?.applyBrightnessOverride(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { target ->
                previousColorMode?.let { target.colorMode = it }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    previousHeadroom?.let { target.desiredHdrHeadroom = it }
                }
            }
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(onBack = onExit)

    var controlsVisible by remember { mutableStateOf(true) }
    var compareMode by remember { mutableStateOf(false) }
    val contrast = if (color.luminance() > 0.45f) Color.Black else Color.White

    // Components above 1.0 in extended sRGB are what carry the colour past SDR white.
    val emitted = if (hdrGain > 1f) {
        Color(
            red = color.red * hdrGain,
            green = color.green * hdrGain,
            blue = color.blue * hdrGain,
            alpha = 1f,
            colorSpace = ColorSpaces.ExtendedSrgb,
        )
    } else {
        color
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { onExit() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (compareMode) {
            // Side by side, so the difference is visible at a glance instead of
            // having to remember how bright the last setting looked.
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(pureColor),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(emitted),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(emitted))
        }

        AnimatedVisibility(visible = controlsVisible) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Tap to hide controls · double-tap or Back to exit",
                    color = contrast.copy(alpha = 0.75f),
                )
                Text(
                    text = "HDR headroom in use: " +
                        if (hdrGain > 1f) "%.2fx".format(hdrGain) else "none",
                    color = contrast.copy(alpha = 0.75f),
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    text = diagnostics,
                    color = contrast.copy(alpha = 0.55f),
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                )
                if (compareMode) {
                    Text(
                        text = "left: pure hue, no boost   |   right: boost + HDR",
                        color = contrast.copy(alpha = 0.75f),
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center,
                    )
                }
                FilledTonalButton(onClick = { compareMode = !compareMode }) {
                    Text(if (compareMode) "Stop comparing" else "Compare with/without boost")
                }
                FilledTonalButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = "  Close lamp")
                }
            }
        }
    }
}

/**
 * Sets the window's brightness override on a fresh copy of the layout params.
 * Mutating the instance [Window.getAttributes] hands back and passing it straight
 * back is the usual idiom, but copying makes the change unambiguous.
 */
private fun Window.applyBrightnessOverride(value: Float) {
    attributes = WindowManager.LayoutParams().apply {
        copyFrom(this@applyBrightnessOverride.attributes)
        screenBrightness = value
    }
}
