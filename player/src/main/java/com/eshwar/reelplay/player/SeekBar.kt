package com.eshwar.reelplay.player

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.dp

/**
 * A thin, streaming-app style seek bar: a 3 dp line with a small dot that both grow while you
 * drag. Shows what's buffered behind the played part. The touch area stays a comfortable
 * 32 dp tall even though the line is thin.
 *
 * [value] and [buffered] are fractions 0..1. [onSeeking] reports the position while the finger
 * is down; [onSeek] fires once on release (or tap) with the final position.
 */
@Composable
fun SeekBar(
    value: Float,
    buffered: Float,
    color: Color,
    onSeeking: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    val track by animateDpAsState(if (dragging) 5.dp else 3.dp, label = "track")
    val thumb by animateDpAsState(if (dragging) 16.dp else 10.dp, label = "thumb")
    val shown = if (dragging) dragValue else value

    Canvas(
        modifier
            .fillMaxWidth()
            .height(32.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(shown, 0f..1f)
                setProgress { onSeek(it.coerceIn(0f, 1f)); true }
            }
            .pointerInput(Unit) {
                detectTapGestures { offset -> onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        dragValue = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeeking(dragValue)
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        dragValue = (change.position.x / size.width).coerceIn(0f, 1f)
                        onSeeking(dragValue)
                    },
                    onDragEnd = {
                        dragging = false
                        onSeek(dragValue)
                    },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        val h = track.toPx()
        val r = CornerRadius(h / 2, h / 2)
        val y = (size.height - h) / 2
        val w = size.width
        drawRoundRect(Color(0x40FFFFFF), Offset(0f, y), Size(w, h), r)
        drawRoundRect(Color(0x66FFFFFF), Offset(0f, y), Size(w * buffered.coerceIn(0f, 1f), h), r)
        drawRoundRect(color, Offset(0f, y), Size(w * shown.coerceIn(0f, 1f), h), r)
        drawCircle(color, thumb.toPx() / 2, Offset(w * shown.coerceIn(0f, 1f), size.height / 2))
    }
}
