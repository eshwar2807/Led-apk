package com.eshwar.reelplay.faces

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eshwar.reelplay.library.VideoThumbnail
import com.eshwar.reelplay.player.PlayerActivity
import com.eshwar.reelplay.ui.formatDuration
import com.eshwar.reelplay.ui.startActivitySafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Full screen photos and videos of one person. Swiping goes through their items only (the
 * phone's gallery app would carry on into every other photo). Videos open in the player with
 * the person's other videos queued after.
 */
@Composable
internal fun PersonViewer(
    items: List<MediaRef>,
    start: Int,
    onDelete: ((MediaRef) -> Unit)?,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        if (items.isEmpty()) {
            onClose()
            return@Dialog
        }
        val context = LocalContext.current
        val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize(), userScrollEnabled = !zoomed, key = { items[it].key }) { page ->
                val item = items[page]
                if (item.isVideo) {
                    Box(
                        Modifier.fillMaxSize().clickable {
                            val videos = items.filter { it.isVideo }
                            context.startActivitySafely(
                                PlayerActivity.intent(context, videos.map { it.uri }, videos.map { it.name }, videos.indexOf(item)),
                            )
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        VideoThumbnail(item.uri, Modifier.fillMaxSize())
                        Box(
                            Modifier.size(72.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(44.dp), tint = Color.White)
                        }
                        Text(
                            formatDuration(item.durationMs),
                            color = Color.White,
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                        )
                    }
                } else {
                    Photo(item, onZoomChange = { if (page == pager.currentPage) zoomed = it })
                }
            }
            val current = items.getOrNull(pager.currentPage)
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.45f)).statusBarsPadding()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                Text(
                    "${pager.currentPage + 1} / ${items.size}" + (current?.let { "  ·  ${it.name}" } ?: ""),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (onDelete != null && current != null) {
                    IconButton(onClick = { onDelete(current) }) { Icon(Icons.Rounded.Delete, "Delete", tint = Color.White) }
                }
            }
        }
    }
}

/** A photo that fits the screen; pinch or double-tap to zoom in. */
@Composable
private fun Photo(item: MediaRef, onZoomChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, item.key) {
        value = withContext(Dispatchers.IO) { runCatching { FaceIndex.decodePhoto(context, item.uri, 2560) }.getOrNull() }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    fun set(s: Float, o: Offset) {
        scale = s.coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else o
        onZoomChange(scale > 1f)
    }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(item.key) {
                detectTapGestures(onDoubleTap = { set(if (scale > 1f) 1f else 2.5f, Offset.Zero) })
            }
            .pointerInput(item.key, scale > 1f) {
                // Only take over drags while zoomed in; at normal size they swipe between items.
                if (scale > 1f) detectTransformGestures { _, pan, zoom, _ -> set(scale * zoom, offset + pan) }
                else awaitEachGesture {
                    // Two fingers pinch; one finger is left alone for the pager.
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                set(scale * zoom, offset)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val b = bitmap
        if (b == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Image(
                b.asImageBitmap(),
                item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
            )
        }
    }
}

internal val canDeleteMedia: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
