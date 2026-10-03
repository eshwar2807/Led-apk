package com.eshwar.reelplay.editor

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.SurfaceView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.eshwar.reelplay.library.VideoThumbnail
import com.eshwar.reelplay.player.PlayerActivity
import com.eshwar.reelplay.ui.formatDuration
import com.eshwar.reelplay.ui.formatSeconds
import com.eshwar.reelplay.ui.formatSize
import com.eshwar.reelplay.ui.startActivitySafely
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

private const val PREVIEW_SHORT_SIDE = 720

@OptIn(UnstableApi::class, ExperimentalApi::class)
@Composable
fun EditorScreen(
    state: EditorState,
    player: CompositionPlayer,
    exporter: Exporter,
    initialUris: List<Uri>,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val project = state.project

    var positionMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var activeTool by remember { mutableStateOf<Tool?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    var exportStage by remember { mutableStateOf<ExportStage?>(null) }
    var exportState by remember { mutableStateOf<Exporter.State>(Exporter.State.Idle) }
    var editingText by remember { mutableStateOf<TextLayer?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var seekTick by remember { mutableIntStateOf(0) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, max(0, project.durationMs - 1))
        positionMs = target
        player.seekTo(target)
        seekTick++
    }

    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            busy = true
            val added = uris.mapNotNull { uri ->
                MediaProbe.probe(context, uri)?.let { info ->
                    Clip(state.newId(), uri, info, 0L, info.durationMs)
                }
            }
            busy = false
            if (added.size < uris.size) toast("${uris.size - added.size} file(s) couldn't be read")
            if (added.isEmpty()) return@launch
            val clips = state.project.clips.toMutableList()
            val at = state.selectedClip?.let { sel -> clips.indexOfFirst { it.id == sel.id } + 1 } ?: clips.size
            clips.addAll(at, added)
            state.commit(state.project.copy(clips = clips))
            state.selectedId = null
        }
    }

    val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) {
        addMedia(it)
    }
    fun pickMedia() = mediaPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))

    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val duration = MediaProbe.audioDuration(context, uri)
            if (duration <= 0) {
                toast("That file has no playable audio")
                return@launch
            }
            state.commit(state.project.copy(music = Music(uri, MediaProbe.displayName(context, uri), duration)))
        }
    }

    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        exportStage = ExportStage.CONFIG
    }

    LaunchedEffect(Unit) {
        if (initialUris.isNotEmpty()) addMedia(initialUris) else pickMedia()
    }

    // Player → UI.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlayerError(error: PlaybackException) {
                previewError = "Preview error: ${error.errorCodeName}"
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(isPlaying) {
        while (isPlaying) {
            positionMs = player.currentPosition
            delay(33)
        }
        positionMs = player.currentPosition
    }

    // Project → preview. Debounced so a slider drag doesn't rebuild the pipeline every frame.
    LaunchedEffect(project) {
        if (project.clips.isEmpty()) {
            player.stop()
            return@LaunchedEffect
        }
        delay(180)
        previewError = null
        val start = positionMs.coerceIn(0, max(0, project.durationMs - 1))
        player.setComposition(CompositionFactory.build(project, PREVIEW_SHORT_SIDE), start)
        player.prepare()
    }

    BackHandler {
        when {
            activeTool != null -> activeTool = null
            state.selectedId != null -> state.selectedId = null
            state.isDirty && project.clips.isNotEmpty() -> confirmExit = true
            else -> onClose()
        }
    }

    fun clipAtPlayhead(): Clip? = project.locate(positionMs)?.let { project.clips[it.first] }

    fun runTool(tool: Tool) {
        val clip = state.selectedClip ?: clipAtPlayhead()
        when (tool) {
            Tool.ADD -> pickMedia()
            Tool.EDIT -> clip?.let { state.selectedId = it.id }
            Tool.AUDIO, Tool.CANVAS -> activeTool = if (activeTool == tool) null else tool
            Tool.SPLIT -> {
                val c = state.selectedClip ?: clip ?: return
                val idx = project.clips.indexOf(c)
                val local = positionMs - project.clipStarts()[idx]
                if (local < Clip.MIN_LENGTH_MS || c.outputDurationMs - local < Clip.MIN_LENGTH_MS) {
                    toast("Move the playhead inside the clip to split it")
                    return
                }
                val cut = c.trimStartMs + if (c.isImage) local else (local * c.speed).toLong()
                val first = c.copy(trimEndMs = cut)
                val second = c.copy(id = state.newId(), trimStartMs = cut)
                val clips = project.clips.toMutableList().apply {
                    set(idx, first)
                    add(idx + 1, second)
                }
                // A split photo is two photos, each lasting its own half.
                if (c.isImage) {
                    clips[idx] = first.copy(trimStartMs = 0, trimEndMs = local)
                    clips[idx + 1] = second.copy(trimStartMs = 0, trimEndMs = c.outputDurationMs - local)
                }
                state.commit(project.copy(clips = clips))
                state.selectedId = second.id
            }
            Tool.DUPLICATE -> {
                val c = clip ?: return
                val idx = project.clips.indexOf(c)
                val copy = c.copy(id = state.newId(), texts = c.texts.map { it.copy(id = state.newId()) })
                state.commit(project.copy(clips = project.clips.toMutableList().apply { add(idx + 1, copy) }))
                state.selectedId = copy.id
            }
            Tool.DELETE -> {
                val c = clip ?: return
                state.commit(project.copy(clips = project.clips.filter { it.id != c.id }))
                state.selectedId = null
                activeTool = null
            }
            Tool.MOVE_LEFT, Tool.MOVE_RIGHT -> {
                val c = clip ?: return
                val idx = project.clips.indexOf(c)
                val to = if (tool == Tool.MOVE_LEFT) idx - 1 else idx + 1
                if (to !in project.clips.indices) return
                val clips = project.clips.toMutableList().apply { add(to, removeAt(idx)) }
                state.commit(project.copy(clips = clips))
            }
            Tool.ROTATE -> clip?.let { c -> state.updateClip(c.id) { it.copy(rotation = (it.rotation + 90) % 360) } }
            Tool.MIRROR -> clip?.let { c -> state.updateClip(c.id) { it.copy(flipH = !it.flipH) } }
            Tool.FLIP -> clip?.let { c -> state.updateClip(c.id) { it.copy(flipV = !it.flipV) } }
            Tool.TEXT -> {
                val c = clip ?: return
                state.selectedId = c.id
                activeTool = if (activeTool == tool) null else tool
                if (c.texts.isEmpty()) editingText = TextLayer(state.newId(), "")
            }
            else -> {
                val c = clip ?: return
                if ((tool == Tool.SPEED || tool == Tool.VOLUME) && c.isImage) {
                    toast("Photos have no ${if (tool == Tool.SPEED) "speed" else "sound"} to change")
                    return
                }
                state.selectedId = c.id
                activeTool = if (activeTool == tool) null else tool
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07090F))
            .safeDrawingPadding(),
    ) {
        // ---- Top bar ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                if (state.isDirty && project.clips.isNotEmpty()) confirmExit = true else onClose()
            }) { Icon(Icons.Rounded.Close, "Close", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = state::undo, enabled = state.canUndo) {
                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (state.canUndo) Color.White else Color.Gray)
            }
            IconButton(onClick = state::redo, enabled = state.canRedo) {
                Icon(Icons.AutoMirrored.Rounded.Redo, "Redo", tint = if (state.canRedo) Color.White else Color.Gray)
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    player.pause()
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    } else {
                        exportStage = ExportStage.CONFIG
                    }
                },
                enabled = project.clips.isNotEmpty() && !busy,
            ) { Text("Export") }
            Spacer(Modifier.width(8.dp))
        }

        // ---- Preview ----
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (project.clips.isEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.AddPhotoAlternate, null,
                        tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Start with your videos and photos", color = Color.White)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = ::pickMedia) { Text("Add media") }
                }
            } else {
                val ratio = project.aspect
                val boxRatio = maxWidth.value / maxHeight.value
                val (w, h) = if (ratio > boxRatio) maxWidth to maxWidth / ratio else maxHeight * ratio to maxHeight
                Box(
                    Modifier.size(w, h).background(Color.Black).clickable {
                        if (player.isPlaying) player.pause() else {
                            if (positionMs >= project.durationMs - 50) seekTo(0)
                            player.play()
                        }
                    },
                ) {
                    AndroidView(
                        factory = { ctx -> SurfaceView(ctx).also { player.setVideoSurfaceView(it) } },
                        onRelease = { player.clearVideoSurfaceView(it) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
            previewError?.let {
                Text(
                    it, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .background(Color(0xCCB3261E), RoundedCornerShape(6.dp)).padding(6.dp),
                )
            }
        }

        // ---- Transport ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${formatDuration(positionMs)} / ${formatDuration(project.durationMs)}",
                color = Color.White, fontSize = 13.sp, modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = {
                    if (isPlaying) player.pause() else {
                        if (positionMs >= project.durationMs - 50) seekTo(0)
                        player.play()
                    }
                },
                enabled = project.clips.isNotEmpty(),
            ) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(32.dp),
                )
            }
            Text(
                project.canvas.label + " · " + project.clips.size + " clip" + if (project.clips.size == 1) "" else "s",
                color = Color.Gray, fontSize = 12.sp, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }

        // ---- Timeline ----
        Timeline(
            project = project,
            selectedId = state.selectedId,
            positionMs = positionMs,
            isPlaying = isPlaying,
            seekTick = seekTick,
            onScrub = { ms ->
                if (player.isPlaying) player.pause()
                positionMs = ms
                player.seekTo(ms)
            },
            onScrubbing = { player.setScrubbingModeEnabled(it) },
            onSelect = { id ->
                state.selectedId = if (state.selectedId == id) null else id
                if (state.selectedId == null) activeTool = null
            },
            onAdd = ::pickMedia,
        )

        // ---- Tool panel ----
        activeTool?.let { tool ->
            Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                ToolPanel(
                    tool = tool,
                    state = state,
                    onClose = { activeTool = null },
                    onPickMusic = { musicPicker.launch(arrayOf("audio/*")) },
                    onEditText = { editingText = it },
                )
            }
        }

        // ---- Toolbar ----
        val selected = state.selectedClip
        Surface(color = Color(0xFF0E121B), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected != null) {
                    IconButton(onClick = { state.selectedId = null; activeTool = null }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Done", tint = Color.White)
                    }
                }
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    val tools = if (selected != null) Tool.clipTools(selected.isImage) else Tool.mainTools
                    items(tools) { tool ->
                        ToolButton(tool, active = activeTool == tool, enabled = project.clips.isNotEmpty() || tool == Tool.ADD) {
                            runTool(tool)
                        }
                    }
                }
            }
        }
    }

    editingText?.let { layer ->
        val clip = state.selectedClip
        TextEditorDialog(
            initial = layer,
            onDismiss = { editingText = null },
            onSave = { saved ->
                editingText = null
                if (clip == null) return@TextEditorDialog
                state.updateClip(clip.id) { c ->
                    val exists = c.texts.any { it.id == saved.id }
                    c.copy(
                        texts = when {
                            saved.text.isBlank() -> c.texts.filter { it.id != saved.id }
                            exists -> c.texts.map { if (it.id == saved.id) saved else it }
                            else -> c.texts + saved
                        },
                    )
                }
                activeTool = Tool.TEXT
            },
        )
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Discard this edit?") },
            text = { Text("Your changes haven't been exported.") },
            confirmButton = { TextButton(onClick = onClose) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("Keep editing") } },
        )
    }

    when (exportStage) {
        ExportStage.CONFIG -> ExportConfigDialog(
            project = project,
            onDismiss = { exportStage = null },
            onExport = { resolution ->
                exportStage = ExportStage.RUNNING
                player.stop()
                exporter.start(
                    CompositionFactory.build(project, resolution),
                    onState = { exportState = it },
                    onFinished = { file ->
                        val size = file.length()
                        scope.launch {
                            exportState = try {
                                Exporter.State.Done(Exporter.publish(context, file), size)
                            } catch (e: Exception) {
                                Exporter.State.Failed(e.message ?: "Couldn't save the video")
                            }
                        }
                    },
                )
            },
        )
        ExportStage.RUNNING -> ExportProgressDialog(
            state = exportState,
            onCancel = {
                exporter.cancel()
                exportStage = null
                exportState = Exporter.State.Idle
                player.prepare()
            },
            onClose = {
                exportStage = null
                exportState = Exporter.State.Idle
                player.prepare()
            },
            onPlay = { uri ->
                context.startActivity(PlayerActivity.intent(context, listOf(uri), listOf("Export"), 0))
            },
            onShare = { uri ->
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "video/mp4"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivitySafely(Intent.createChooser(send, "Share video"), "Couldn't share")
            },
        )
        null -> Unit
    }
}

private enum class ExportStage { CONFIG, RUNNING }

@Composable
private fun Timeline(
    project: Project,
    selectedId: Long?,
    positionMs: Long,
    isPlaying: Boolean,
    seekTick: Int,
    onScrub: (Long) -> Unit,
    onScrubbing: (Boolean) -> Unit,
    onSelect: (Long) -> Unit,
    onAdd: () -> Unit,
) {
    val density = LocalDensity.current
    val total = project.durationMs
    // Long projects get squeezed so the whole thing stays scrollable in a few swipes.
    val dpPerSecond = remember(total) {
        val seconds = max(1f, total / 1000f)
        (2400f / seconds).coerceIn(6f, 64f)
    }
    val pxPerMs = with(density) { dpPerSecond.dp.toPx() } / 1000f
    val scroll = rememberScrollState()
    val dragged by scroll.interactionSource.collectIsDraggedAsState()
    var userScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(dragged) {
        if (dragged) {
            userScrolling = true
            onScrubbing(true)
        }
    }
    LaunchedEffect(scroll.isScrollInProgress, dragged) {
        if (!scroll.isScrollInProgress && !dragged && userScrolling) {
            userScrolling = false
            onScrubbing(false)
        }
    }
    // Finger on the timeline: the timeline drives the player.
    LaunchedEffect(pxPerMs, total) {
        snapshotFlow { scroll.value }.collect { value ->
            if (userScrolling) onScrub((value / pxPerMs).toLong().coerceIn(0, max(0, total - 1)))
        }
    }
    // Otherwise the player drives the timeline.
    LaunchedEffect(positionMs, pxPerMs, seekTick, isPlaying) {
        if (!userScrolling) scroll.scrollTo((positionMs * pxPerMs).toInt())
    }

    BoxWithConstraints(Modifier.fillMaxWidth().height(if (project.music != null) 112.dp else 88.dp)) {
        val half = maxWidth / 2
        Column(
            Modifier
                .fillMaxHeight()
                .horizontalScroll(scroll)
                .padding(start = half, end = 0.dp, top = 8.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                project.clips.forEach { clip ->
                    val width = with(density) { (clip.outputDurationMs * pxPerMs).toDp() }
                    ClipBlock(clip, width, selected = clip.id == selectedId) { onSelect(clip.id) }
                }
                Spacer(Modifier.width(12.dp))
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.White).clickable(onClick = onAdd),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Add, "Add media", tint = Color.Black) }
                Spacer(Modifier.width((half - 60.dp).coerceAtLeast(0.dp)))
            }
            project.music?.let { music ->
                Spacer(Modifier.height(4.dp))
                val width = with(density) { (total * pxPerMs).toDp() }
                Row(
                    Modifier.width(width).height(20.dp).clip(RoundedCornerShape(4.dp))
                        .background(Color(0xFF2E7D5B)).padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(music.name, color = Color.White, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Playhead.
        Box(
            Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(Color.White),
        )
    }
}

@Composable
private fun ClipBlock(clip: Clip, width: androidx.compose.ui.unit.Dp, selected: Boolean, onClick: () -> Unit) {
    val frames = max(1, min(24, ceil(width.value / 48f).toInt()))
    Box(
        Modifier
            .width(width)
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(
                if (selected) 2.dp else 0.5.dp,
                if (selected) Color.White else Color(0x33FFFFFF),
                RoundedCornerShape(6.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.fillMaxSize()) {
            for (i in 0 until frames) {
                val t = if (clip.isImage) null
                else (clip.trimStartMs + clip.sourceSpanMs * (i + 0.5f) / frames).toLong() * 1000
                VideoThumbnail(clip.uri, Modifier.weight(1f).fillMaxHeight(), timeUs = t)
            }
        }
        Row(
            Modifier.align(Alignment.TopStart).padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            if (clip.speed != 1f) Badge("${clip.speed}×")
            if (clip.texts.isNotEmpty()) BadgeIcon(Icons.Rounded.TextFields)
            if (clip.volume == 0f) BadgeIcon(Icons.AutoMirrored.Rounded.VolumeOff)
        }
        if (width > 44.dp) {
            Text(
                formatSeconds(clip.outputDurationMs), color = Color.White, fontSize = 10.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(3.dp)
                    .background(Color(0x99000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
            )
        }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
    )
}

@Composable
private fun BadgeIcon(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        icon, null, tint = Color.White,
        modifier = Modifier.size(14.dp).background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(1.dp),
    )
}

@Composable
private fun ToolButton(tool: Tool, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = when {
        !enabled -> Color.Gray
        active -> MaterialTheme.colorScheme.primary
        tool == Tool.DELETE -> Color(0xFFFF8A80)
        else -> Color.White
    }
    Column(
        Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tool.icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(tool.label, color = tint, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun ExportConfigDialog(project: Project, onDismiss: () -> Unit, onExport: (Int) -> Unit) {
    var resolution by remember { mutableIntStateOf(1080) }
    val (w, h) = project.outputSize(resolution)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export") },
        text = {
            Column {
                Text("Resolution", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((label, value) in listOf("480p" to 480, "720p" to 720, "1080p" to 1080, "2K" to 1440)) {
                        FilterChip(
                            selected = resolution == value,
                            onClick = { resolution = value },
                            label = { Text(label) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "$w × $h · ${formatDuration(project.durationMs)} · H.264 MP4",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Saved to Movies/ReelPlay",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { Button(onClick = { onExport(resolution) }) { Text("Export") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ExportProgressDialog(
    state: Exporter.State,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onPlay: (Uri) -> Unit,
    onShare: (Uri) -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(
                when (state) {
                    is Exporter.State.Done -> "Saved"
                    is Exporter.State.Failed -> "Export failed"
                    else -> "Exporting…"
                },
            )
        },
        text = {
            Column {
                when (state) {
                    is Exporter.State.Running -> {
                        val p = state.progress
                        if (p != null) {
                            LinearProgressIndicator(progress = { p / 100f }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            Text("$p%")
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Keep the app open until it finishes.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    is Exporter.State.Done -> Text("${formatSize(state.sizeBytes)} · Movies/ReelPlay")
                    is Exporter.State.Failed -> Text(state.message)
                    Exporter.State.Idle -> LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            when (state) {
                is Exporter.State.Done -> Row {
                    TextButton(onClick = { onShare(state.uri) }) { Text("Share") }
                    TextButton(onClick = { onPlay(state.uri) }) { Text("Play") }
                }
                is Exporter.State.Failed -> TextButton(onClick = onClose) { Text("OK") }
                else -> TextButton(onClick = onCancel) { Text("Cancel") }
            }
        },
        dismissButton = if (state is Exporter.State.Done) ({ TextButton(onClick = onClose) { Text("Done") } }) else null,
    )
}
