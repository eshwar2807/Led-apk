package com.eshwar.reelplay.player

import android.content.Context
import android.media.AudioManager
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.eshwar.reelplay.report.ReportDialog
import com.eshwar.reelplay.report.Reports
import com.eshwar.reelplay.settings.AppSettings
import com.eshwar.reelplay.torrent.TorrentEngine
import com.eshwar.reelplay.torrent.describe
import com.eshwar.reelplay.ui.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
private enum class ResizeMode(val label: String, val mode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    CROP("Crop", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    STRETCH("Stretch", AspectRatioFrameLayout.RESIZE_MODE_FILL),
}

private enum class DragMode { NONE, SEEK, BRIGHTNESS, VOLUME, ZOOM }

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(player: ExoPlayer, host: PlayerHost, inPip: Boolean) {
    val context = LocalContext.current
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1) }

    // ---- Player state, mirrored into Compose ----
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var title by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf(player.currentTracks) }
    var hasNext by remember { mutableStateOf(false) }
    var hasPrev by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableIntStateOf(player.repeatMode) }
    var shuffle by remember { mutableStateOf(player.shuffleModeEnabled) }
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    var buffering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var errorDetails by remember { mutableStateOf("") }
    var reporting by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onEvents(p: Player, events: Player.Events) {
                isPlaying = p.isPlaying
                title = p.mediaMetadata.title?.toString() ?: ""
                tracks = p.currentTracks
                hasNext = p.hasNextMediaItem()
                hasPrev = p.hasPreviousMediaItem()
                repeatMode = p.repeatMode
                shuffle = p.shuffleModeEnabled
                speed = p.playbackParameters.speed
                buffering = p.playbackState == Player.STATE_BUFFERING
                duration = p.duration.coerceAtLeast(0)
            }

            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                host.recoverFrom(e)?.let { what ->
                    android.widget.Toast.makeText(context, what, android.widget.Toast.LENGTH_LONG).show()
                    return
                }
                error = DecoderRecovery.explain(e, player)
                errorDetails = "Playback error ${e.errorCodeName}\n" +
                    "Link: ${player.currentMediaItem?.localConfiguration?.uri}\n\n" + e.stackTraceToString().take(8_000)
            }
        }
        player.addListener(listener)
        listener.onEvents(player, Player.Events(androidx.media3.common.FlagSet.Builder().build()))
        onDispose { player.removeListener(listener) }
    }

    val accent = AppSettings.accent.collectAsState().value.color

    // ---- UI state ----
    var controlsVisible by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    var resize by remember { mutableStateOf(ResizeMode.FIT) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var showRemaining by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<GestureHint?>(null) }
    var hintTick by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<PlayerDialog?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var sleepAtMs by remember { mutableStateOf<Long?>(null) }
    var sleepAtEnd by remember { mutableStateOf(false) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    fun poke() {
        lastInteraction = System.currentTimeMillis()
    }

    fun flash(h: GestureHint) {
        hint = h
        hintTick++
    }

    // Torrent download numbers, refreshed once a second while one is playing.
    var torrentLine by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(player) {
        while (true) {
            // Player state is main-thread only; just the libtorrent query goes to IO.
            val stream = player.currentMediaItem?.localConfiguration?.uri?.let(TorrentEngine::stream)
            torrentLine = stream?.let { withContext(Dispatchers.IO) { it.stats() } }?.let(::describe)
            delay(1000)
        }
    }

    // Position ticker.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            buffered = player.bufferedPosition
            duration = player.duration.coerceAtLeast(0)
            delay(200)
        }
    }

    // Auto-hide controls while playing.
    LaunchedEffect(controlsVisible, isPlaying, lastInteraction, dialog, moreMenu) {
        if (controlsVisible && isPlaying && dialog == null && !moreMenu) {
            delay(3500)
            controlsVisible = false
        }
    }

    // Brief on-screen readouts fade by themselves.
    LaunchedEffect(hintTick) {
        if (hint?.sticky == false) {
            delay(700)
            hint = null
        }
    }

    // Sleep timer.
    LaunchedEffect(sleepAtMs) {
        val at = sleepAtMs ?: return@LaunchedEffect
        delay((at - System.currentTimeMillis()).coerceAtLeast(0))
        player.pause()
        sleepAtMs = null
    }
    LaunchedEffect(sleepAtEnd) { player.pauseAtEndOfMediaItems = sleepAtEnd }

    BackHandler(enabled = locked) { flash(GestureHint("Controls locked", Icons.Rounded.Lock)) }

    // ---- Gesture bookkeeping ----
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    var seekStart by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableLongStateOf(0L) }
    var brightness by remember { mutableFloatStateOf(0.5f) }
    var volumeLevel by remember { mutableFloatStateOf(0f) } // 0..2 (1 = system max, above = boost)
    val touchSlop = LocalViewConfiguration.current.touchSlop

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    this.player = player
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    setKeepContentOnPlayerReset(true)
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                }
            },
            update = { it.resizeMode = resize.mode },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = zoom
                    scaleY = zoom
                },
        )

        if (!inPip) {
            // Gesture surface: taps and drags over the whole video.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(locked) {
                        detectTapGestures(
                            onTap = {
                                poke()
                                controlsVisible = !controlsVisible
                            },
                            onDoubleTap = { offset ->
                                if (locked) return@detectTapGestures
                                poke()
                                val third = size.width / 3f
                                when {
                                    offset.x < third -> {
                                        player.seekBack()
                                        flash(GestureHint("−10s", Icons.Rounded.FastRewind))
                                    }
                                    offset.x > third * 2 -> {
                                        player.seekForward()
                                        flash(GestureHint("+10s", Icons.Rounded.FastForward))
                                    }
                                    else -> {
                                        if (player.isPlaying) player.pause() else player.play()
                                        flash(
                                            GestureHint(
                                                if (player.isPlaying) "Play" else "Pause",
                                                if (player.isPlaying) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(locked) {
                        if (locked) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            var mode = DragMode.NONE
                            var totalX = 0f
                            var totalY = 0f
                            val startX = down.position.x
                            do {
                                val event = awaitPointerEvent()
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2 && (mode == DragMode.NONE || mode == DragMode.ZOOM)) {
                                    mode = DragMode.ZOOM
                                    zoom = (zoom * event.calculateZoom()).coerceIn(0.5f, 4f)
                                    flash(GestureHint("Zoom ${(zoom * 100).roundToInt()}%", Icons.Rounded.AspectRatio))
                                    event.changes.forEach { it.consume() }
                                } else if (pressed.size == 1 && mode != DragMode.ZOOM) {
                                    val change = pressed[0]
                                    val delta = change.positionChange()
                                    totalX += delta.x
                                    totalY += delta.y
                                    if (mode == DragMode.NONE && (abs(totalX) > touchSlop || abs(totalY) > touchSlop)) {
                                        mode = when {
                                            abs(totalX) > abs(totalY) -> DragMode.SEEK
                                            startX < size.width / 2f -> DragMode.BRIGHTNESS
                                            else -> DragMode.VOLUME
                                        }
                                        dragMode = mode
                                        when (mode) {
                                            DragMode.SEEK -> {
                                                seekStart = player.currentPosition
                                                seekTarget = seekStart
                                                // Scrubbing mode decodes frames as fast as the finger
                                                // moves, so the picture follows the swipe live.
                                                player.isScrubbingModeEnabled = true
                                            }
                                            DragMode.BRIGHTNESS -> brightness = host.currentBrightness()
                                            DragMode.VOLUME -> {
                                                val sys = audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume
                                                volumeLevel = if (volumeLevel > 1f && sys >= 1f) volumeLevel else sys
                                            }
                                            else -> Unit
                                        }
                                    }
                                    if (mode != DragMode.NONE) {
                                        change.consume()
                                        when (mode) {
                                            DragMode.SEEK -> {
                                                // A full swipe across the screen is about a minute and a half.
                                                val span = 90_000f
                                                seekTarget = (seekStart + totalX / size.width * span).toLong()
                                                    .coerceIn(0L, duration.coerceAtLeast(0L))
                                                player.seekTo(seekTarget)
                                                val diff = seekTarget - seekStart
                                                val sign = if (diff >= 0) "+" else "−"
                                                hint = GestureHint(
                                                    "$sign${formatDuration(abs(diff))}\n${formatDuration(seekTarget)}",
                                                    if (diff >= 0) Icons.Rounded.FastForward else Icons.Rounded.FastRewind,
                                                    sticky = true,
                                                )
                                            }
                                            DragMode.BRIGHTNESS -> {
                                                brightness = (brightness - delta.y / (size.height * 0.7f)).coerceIn(0.01f, 1f)
                                                host.setBrightness(brightness)
                                                hint = GestureHint(
                                                    "Brightness ${(brightness * 100).roundToInt()}%",
                                                    Icons.Rounded.BrightnessMedium,
                                                    progress = brightness, sticky = true,
                                                )
                                            }
                                            DragMode.VOLUME -> {
                                                val ceiling = if (host.canBoost) 2f else 1f
                                                volumeLevel = (volumeLevel - delta.y / (size.height * 0.7f)).coerceIn(0f, ceiling)
                                                val sysVol = (volumeLevel.coerceAtMost(1f) * maxVolume).roundToInt()
                                                if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != sysVol) {
                                                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, sysVol, 0)
                                                }
                                                host.setBoost((volumeLevel - 1f).coerceAtLeast(0f))
                                                hint = GestureHint(
                                                    "Volume ${(volumeLevel * 100).roundToInt()}%" +
                                                        if (volumeLevel > 1f) " (boost)" else "",
                                                    Icons.AutoMirrored.Rounded.VolumeUp,
                                                    progress = volumeLevel / ceiling, sticky = true,
                                                )
                                            }
                                            else -> Unit
                                        }
                                    }
                                }
                            } while (event.changes.any { it.pressed })
                            if (mode == DragMode.SEEK) {
                                player.seekTo(seekTarget)
                                player.isScrubbingModeEnabled = false
                            }
                            if (mode != DragMode.NONE) {
                                dragMode = DragMode.NONE
                                hint = hint?.copy(sticky = false)
                                hintTick++
                            }
                        }
                    },
            )
        }

        if (buffering && !inPip) {
            LinearProgressIndicator(
                Modifier.fillMaxWidth().align(Alignment.TopCenter),
                color = accent,
            )
            // When a torrent stalls, say why instead of spinning silently.
            if (!controlsVisible) {
                torrentLine?.let {
                    Text(
                        "Waiting for torrent data · $it",
                        color = Color.White, fontSize = 12.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(16.dp)
                            .background(Color(0x99000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }

        // Gesture readout in the middle of the screen.
        hint?.let { h ->
            if (!inPip) {
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .background(Color(0xB3000000), RoundedCornerShape(16.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(h.icon, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        h.text, color = Color.White, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    h.progress?.let {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { it.coerceIn(0f, 1f) },
                            modifier = Modifier.width(140.dp),
                        )
                    }
                }
            }
        }

        error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(msg, color = Color.White)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    FilledTonalButton(onClick = {
                        error = null
                        player.prepare()
                        player.play()
                    }) { Text("Retry") }
                    if (hasNext) {
                        FilledTonalButton(onClick = {
                            error = null
                            player.seekToNextMediaItem()
                            player.prepare()
                        }) { Text("Next video") }
                    }
                    FilledTonalButton(onClick = { reporting = true }) { Text("Report") }
                }
            }
        }
        if (reporting) {
            ReportDialog(Reports.Kind.PLAYBACK, details = errorDetails, onDismiss = { reporting = false })
        }

        // ---- Controls ----
        AnimatedVisibility(
            visible = controlsVisible && !inPip,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (locked) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    IconButton(
                        onClick = {
                            locked = false
                            poke()
                        },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .padding(16.dp)
                            .background(Color(0x99000000), CircleShape),
                    ) {
                        Icon(Icons.Rounded.LockOpen, "Unlock", tint = Color.White)
                    }
                }
            } else {
                Box(Modifier.fillMaxSize()) {
                    // Top bar.
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = host::close) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Medium,
                            )
                            torrentLine?.let {
                                Text(
                                    it, color = Color(0xCCFFFFFF), fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        BarButton(Icons.Rounded.Audiotrack, "Audio track") { poke(); dialog = PlayerDialog.AUDIO }
                        BarButton(Icons.Rounded.ClosedCaption, "Subtitles") { poke(); dialog = PlayerDialog.SUBTITLES }
                        BarButton(Icons.Rounded.Speed, "Speed") { poke(); dialog = PlayerDialog.SPEED }
                        Box {
                            BarButton(Icons.Rounded.MoreVert, "More") { poke(); moreMenu = true }
                            DropdownMenu(moreMenu, onDismissRequest = { moreMenu = false }) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when (repeatMode) {
                                                Player.REPEAT_MODE_ONE -> "Repeat: this video"
                                                Player.REPEAT_MODE_ALL -> "Repeat: all"
                                                else -> "Repeat: off"
                                            },
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne
                                            else Icons.Rounded.Repeat,
                                            null,
                                        )
                                    },
                                    onClick = {
                                        player.repeatMode = when (repeatMode) {
                                            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                                            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                                            else -> Player.REPEAT_MODE_OFF
                                        }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(if (shuffle) "Shuffle: on" else "Shuffle: off") },
                                    leadingIcon = { Icon(Icons.Rounded.Shuffle, null) },
                                    onClick = { player.shuffleModeEnabled = !shuffle },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            when {
                                                sleepAtEnd -> "Sleep: end of video"
                                                sleepAtMs != null -> "Sleep: ${formatDuration(sleepAtMs!! - System.currentTimeMillis())} left"
                                                else -> "Sleep timer"
                                            },
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Rounded.Bedtime, null) },
                                    onClick = { moreMenu = false; dialog = PlayerDialog.SLEEP },
                                )
                                DropdownMenuItem(
                                    text = { Text("Load subtitle file…") },
                                    leadingIcon = { Icon(Icons.Rounded.Subtitles, null) },
                                    onClick = { moreMenu = false; host.pickSubtitle() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Edit this video") },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCut, null) },
                                    onClick = { moreMenu = false; host.openInEditor() },
                                )
                            }
                        }
                    }

                    // Centre transport.
                    Row(
                        Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        RoundButton(Icons.Rounded.SkipPrevious, "Previous", 52.dp, enabled = hasPrev || position > 3000) {
                            poke(); player.seekToPrevious()
                        }
                        RoundButton(
                            if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (isPlaying) "Pause" else "Play", 72.dp,
                        ) {
                            poke()
                            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                            if (isPlaying) player.pause() else player.play()
                        }
                        RoundButton(Icons.Rounded.SkipNext, "Next", 52.dp, enabled = hasNext) {
                            poke(); player.seekToNextMediaItem()
                        }
                    }

                    // Bottom bar: seek bar and tools.
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                            .windowInsetsPadding(WindowInsets.safeDrawing)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val shown = scrubbing?.let { (it * duration).toLong() } ?: position
                            Text(formatDuration(shown), color = Color.White, fontSize = 13.sp)
                            SeekBar(
                                value = if (duration > 0) position.toFloat() / duration else 0f,
                                buffered = if (duration > 0) buffered.toFloat() / duration else 0f,
                                color = accent,
                                onSeeking = {
                                    poke()
                                    if (scrubbing == null) player.isScrubbingModeEnabled = true
                                    scrubbing = it
                                    // Live: the video follows the thumb while it's dragged.
                                    player.seekTo((it * duration).toLong())
                                },
                                onSeek = {
                                    poke()
                                    player.seekTo((it * duration).toLong())
                                    player.isScrubbingModeEnabled = false
                                    position = (it * duration).toLong()
                                    scrubbing = null
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                            )
                            Text(
                                if (showRemaining) "−" + formatDuration(duration - shown) else formatDuration(duration),
                                color = Color.White, fontSize = 13.sp,
                                modifier = Modifier.clickable { showRemaining = !showRemaining },
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            BarButton(Icons.Rounded.Lock, "Lock") {
                                locked = true
                                flash(GestureHint("Locked — tap the screen, then the lock, to unlock", Icons.Rounded.Lock))
                            }
                            BarButton(Icons.Rounded.ScreenRotation, "Rotate") {
                                poke()
                                flash(GestureHint(host.cycleOrientation(), Icons.Rounded.ScreenRotation))
                            }
                            Spacer(Modifier.weight(1f))
                            if (speed != 1f) {
                                Text(
                                    "${trimFloat(speed)}×", color = accent,
                                    fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            BarButton(Icons.Rounded.ContentCut, "Edit") { host.openInEditor() }
                            BarButton(Icons.Rounded.PictureInPictureAlt, "Picture in picture") { host.enterPip() }
                            BarButton(Icons.Rounded.AspectRatio, "Aspect ratio") {
                                poke()
                                resize = ResizeMode.entries[(resize.ordinal + 1) % ResizeMode.entries.size]
                                zoom = 1f
                                flash(GestureHint(resize.label, Icons.Rounded.AspectRatio))
                            }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        PlayerDialog.SPEED -> SpeedDialog(
            current = speed,
            onPick = {
                player.setPlaybackSpeed(it)
                host.rememberSpeed(it)
            },
            onDismiss = { dialog = null },
        )
        PlayerDialog.AUDIO -> TrackDialog(
            title = "Audio track",
            options = trackOptions(tracks, C.TRACK_TYPE_AUDIO),
            allowOff = false,
            offSelected = false,
            onSelect = { selectTrack(player, it, C.TRACK_TYPE_AUDIO) },
            onDismiss = { dialog = null },
        )
        PlayerDialog.SUBTITLES -> TrackDialog(
            title = "Subtitles",
            options = trackOptions(tracks, C.TRACK_TYPE_TEXT),
            allowOff = true,
            offSelected = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                trackOptions(tracks, C.TRACK_TYPE_TEXT).none { it.selected },
            onSelect = { selectTrack(player, it, C.TRACK_TYPE_TEXT) },
            onDismiss = { dialog = null },
            footer = {
                TextButton(onClick = {
                    dialog = null
                    host.pickSubtitle()
                }) { Text("Load from file…") }
            },
        )
        PlayerDialog.SLEEP -> SleepDialog(
            onPick = { minutes ->
                when (minutes) {
                    null -> {
                        sleepAtMs = null
                        sleepAtEnd = false
                    }
                    -1 -> {
                        sleepAtMs = null
                        sleepAtEnd = true
                    }
                    else -> {
                        sleepAtEnd = false
                        sleepAtMs = System.currentTimeMillis() + minutes * 60_000L
                    }
                }
                dialog = null
            },
            onDismiss = { dialog = null },
        )
        null -> Unit
    }
}

private data class GestureHint(
    val text: String,
    val icon: ImageVector,
    val progress: Float? = null,
    val sticky: Boolean = false,
)

private enum class PlayerDialog { SPEED, AUDIO, SUBTITLES, SLEEP }

private data class TrackOption(
    val group: Tracks.Group,
    val index: Int,
    val label: String,
    val selected: Boolean,
)

private fun trackOptions(tracks: Tracks, type: Int): List<TrackOption> {
    val out = ArrayList<TrackOption>()
    var n = 1
    for (group in tracks.groups) {
        if (group.type != type) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val f = group.getTrackFormat(i)
            val language = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage }
            val parts = listOfNotNull(
                f.label ?: language ?: "Track $n",
                if (f.label != null) language else null,
                if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) channelLabel(f.channelCount) else null,
            )
            out += TrackOption(group, i, parts.distinct().joinToString(" · "), group.isTrackSelected(i))
            n++
        }
    }
    return out
}

private fun channelLabel(channels: Int) = when (channels) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels ch"
}

private fun selectTrack(player: Player, option: TrackOption?, type: Int) {
    val params = player.trackSelectionParameters.buildUpon()
    if (option == null) {
        params.setTrackTypeDisabled(type, true)
    } else {
        params.setTrackTypeDisabled(type, false)
        params.setOverrideForType(TrackSelectionOverride(option.group.mediaTrackGroup, option.index))
    }
    player.trackSelectionParameters = params.build()
}

private fun trimFloat(f: Float): String =
    if (f == f.toInt().toFloat()) f.toInt().toString() else f.toString()

@Composable
private fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, description, tint = Color.White) }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    description: String,
    size: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(size).clip(CircleShape).background(Color(0x66000000)),
    ) {
        Icon(
            icon, description,
            tint = if (enabled) Color.White else Color(0x66FFFFFF),
            modifier = Modifier.size(size * 0.6f),
        )
    }
}

@Composable
private fun SpeedDialog(current: Float, onPick: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Playback speed: ${trimFloat(value)}×") },
        text = {
            Column {
                Slider(
                    value = value,
                    onValueChange = {
                        value = (it * 20).roundToInt() / 20f
                        onPick(value)
                    },
                    valueRange = 0.25f..4f,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (s in listOf(0.5f, 1f, 1.5f, 2f, 3f)) {
                        TextButton(onClick = { value = s; onPick(s) }) { Text("${trimFloat(s)}×") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = { TextButton(onClick = { value = 1f; onPick(1f) }) { Text("Reset") } },
    )
}

@Composable
private fun TrackDialog(
    title: String,
    options: List<TrackOption>,
    allowOff: Boolean,
    offSelected: Boolean,
    onSelect: (TrackOption?) -> Unit,
    onDismiss: () -> Unit,
    footer: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn {
                if (allowOff) {
                    item {
                        OptionRow("Off", offSelected) { onSelect(null); onDismiss() }
                    }
                }
                items(options) { option ->
                    OptionRow(option.label, option.selected && !offSelected) { onSelect(option); onDismiss() }
                }
                if (options.isEmpty()) {
                    item {
                        Text(
                            "No ${title.lowercase()} in this video.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = { footer?.invoke() ?: TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = if (footer != null) ({ TextButton(onClick = onDismiss) { Text("Close") } }) else null,
    )
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

@Composable
private fun SleepDialog(onPick: (Int?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sleep timer") },
        text = {
            Column {
                for (m in listOf(15, 30, 45, 60, 90)) {
                    OptionRow("$m minutes", false) { onPick(m) }
                }
                OptionRow("End of current video", false) { onPick(-1) }
                OptionRow("Off", false) { onPick(null) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
