package com.eshwar.torchglow.ui

import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Full-screen coloured lamp. The display is the only part of the phone that can
 * actually emit a chosen colour, so this is where the wheel's colour lands.
 */
@Composable
fun ScreenLight(
    color: Color,
    onExit: () -> Unit,
) {
    val view = LocalView.current
    val activity = LocalActivity.current

    // Maximum screen brightness + immersive full screen while the lamp is up.
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val previousBrightness = window?.attributes?.screenBrightness
        window?.let {
            it.attributes = it.attributes.apply { screenBrightness = 1f }
            it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            window?.let {
                it.attributes = it.attributes.apply {
                    screenBrightness = previousBrightness
                        ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
                it.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler(onBack = onExit)

    var controlsVisible by remember { mutableStateOf(true) }
    // Keep the exit affordance readable on both pale and dark colours.
    val contrast = if (color.luminance() > 0.45f) Color.Black else Color.White

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(color)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { onExit() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
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
                FilledTonalButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(text = "  Close lamp")
                }
            }
        }
    }
}
