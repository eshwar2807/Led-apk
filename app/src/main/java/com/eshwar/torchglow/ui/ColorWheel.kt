package com.eshwar.torchglow.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * HSV colour wheel: angle picks the hue, distance from the centre picks the
 * saturation. The [value] only dims the preview — the brightness slider owns it.
 */
@Composable
fun ColorWheel(
    hue: Float,
    saturation: Float,
    value: Float,
    onColorChange: (hue: Float, saturation: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .aspectRatio(1f)
            .pointerInput(Unit) {
                detectTapGestures { position -> emit(position, size.width, size.height, onColorChange) }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position -> emit(position, size.width, size.height, onColorChange) },
                ) { change, _ ->
                    change.consume()
                    emit(change.position, size.width, size.height, onColorChange)
                }
            },
    ) {
        val radius = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Hue ring: a sweep gradient walking the full spectrum.
        drawCircle(
            brush = Brush.sweepGradient(
                colors = HUE_STOPS.map { Color.hsv(it, 1f, 1f) },
                center = center,
            ),
            radius = radius,
            center = center,
        )
        // Saturation: white in the middle fading out to the fully saturated rim.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White, Color.Transparent),
                center = center,
                radius = radius,
            ),
            radius = radius,
            center = center,
        )
        // Brightness preview.
        if (value < 1f) {
            drawCircle(
                color = Color.Black.copy(alpha = 1f - value),
                radius = radius,
                center = center,
            )
        }

        // Selection puck.
        val angle = Math.toRadians(hue.toDouble())
        val puck = Offset(
            x = center.x + (cos(angle) * saturation * radius).toFloat(),
            y = center.y + (sin(angle) * saturation * radius).toFloat(),
        )
        val puckRadius = 14.dp.toPx()
        drawCircle(color = Color.hsv(hue, saturation, value), radius = puckRadius, center = puck)
        drawCircle(
            color = Color.White,
            radius = puckRadius,
            center = puck,
            style = Stroke(width = 3.dp.toPx()),
        )
        drawCircle(
            color = Color.Black.copy(alpha = 0.35f),
            radius = puckRadius + 3.dp.toPx(),
            center = puck,
            style = Stroke(width = 1.dp.toPx()),
        )
    }
}

private fun emit(
    position: Offset,
    width: Int,
    height: Int,
    onColorChange: (hue: Float, saturation: Float) -> Unit,
) {
    val radius = min(width, height) / 2f
    if (radius <= 0f) return
    val dx = position.x - width / 2f
    val dy = position.y - height / 2f
    val saturation = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
    val hue = ((Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() % 360f) + 360f) % 360f
    onColorChange(hue, saturation)
}

private val HUE_STOPS = listOf(
    0f, 30f, 60f, 90f, 120f, 150f, 180f, 210f, 240f, 270f, 300f, 330f, 360f,
)
