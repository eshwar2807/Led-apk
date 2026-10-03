package com.eshwar.reelplay.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material.icons.rounded.PhotoFilter
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VerticalSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eshwar.reelplay.ui.formatSeconds
import java.util.Locale
import kotlin.math.roundToInt

enum class Tool(val label: String, val icon: ImageVector) {
    EDIT("Edit", Icons.Rounded.ContentCut),
    ADD("Add", Icons.Rounded.AddPhotoAlternate),
    AUDIO("Audio", Icons.Rounded.MusicNote),
    TEXT("Text", Icons.Rounded.TextFields),
    FILTERS("Filters", Icons.Rounded.PhotoFilter),
    ADJUST("Adjust", Icons.Rounded.Tune),
    CANVAS("Format", Icons.Rounded.AspectRatio),
    SPLIT("Split", Icons.Rounded.VerticalSplit),
    TRIM("Trim", Icons.Rounded.ContentCut),
    SPEED("Speed", Icons.Rounded.Speed),
    VOLUME("Volume", Icons.AutoMirrored.Rounded.VolumeUp),
    ROTATE("Rotate", Icons.Rounded.Rotate90DegreesCw),
    MIRROR("Mirror", Icons.Rounded.Flip),
    FLIP("Flip", Icons.Rounded.SwapVert),
    DUPLICATE("Duplicate", Icons.Rounded.ContentCopy),
    MOVE_LEFT("Move left", Icons.Rounded.KeyboardDoubleArrowLeft),
    MOVE_RIGHT("Move right", Icons.Rounded.KeyboardDoubleArrowRight),
    DELETE("Delete", Icons.Rounded.Delete);

    companion object {
        val mainTools = listOf(EDIT, ADD, AUDIO, TEXT, FILTERS, ADJUST, CANVAS)

        fun clipTools(isImage: Boolean): List<Tool> =
            listOfNotNull(
                SPLIT, if (isImage) null else SPEED, TRIM, if (isImage) null else VOLUME,
                FILTERS, ADJUST, TEXT, ROTATE, MIRROR, FLIP, DUPLICATE, MOVE_LEFT, MOVE_RIGHT, DELETE,
            )
    }
}

@Composable
fun ToolPanel(
    tool: Tool,
    state: EditorState,
    onClose: () -> Unit,
    onPickMusic: () -> Unit,
    onEditText: (TextLayer) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tool.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            IconButton(onClick = { state.settle(); onClose() }) { Icon(Icons.Rounded.Check, "Done") }
        }
        val clip = state.selectedClip
        when (tool) {
            Tool.TRIM -> clip?.let { TrimPanel(it, state) }
            Tool.SPEED -> clip?.let { SpeedPanel(it, state) }
            Tool.VOLUME -> clip?.let { VolumePanel(it, state) }
            Tool.FILTERS -> clip?.let { FilterPanel(it, state) }
            Tool.ADJUST -> clip?.let { AdjustPanel(it, state) }
            Tool.TEXT -> clip?.let { TextPanel(it, state, onEditText) }
            Tool.AUDIO -> AudioPanel(state, onPickMusic)
            Tool.CANVAS -> CanvasPanel(state)
            else -> Unit
        }
    }
}

@Composable
private fun TrimPanel(clip: Clip, state: EditorState) {
    if (clip.isImage) {
        LabeledSlider(
            label = "Duration",
            value = clip.sourceSpanMs / 1000f,
            range = 0.5f..15f,
            display = { formatSeconds((it * 1000).toLong()) },
            onChange = { v -> state.updateClip(clip.id, live = true) { it.copy(trimStartMs = 0, trimEndMs = (v * 1000).toLong()) } },
            onDone = state::settle,
        )
        return
    }
    val full = clip.info.durationMs.toFloat()
    Text(
        "${formatSeconds(clip.trimStartMs)} – ${formatSeconds(clip.trimEndMs)}  (${formatSeconds(clip.sourceSpanMs)} of ${formatSeconds(clip.info.durationMs)})",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RangeSlider(
        value = clip.trimStartMs.toFloat()..clip.trimEndMs.toFloat(),
        onValueChange = { range ->
            var start = range.start.toLong()
            var end = range.endInclusive.toLong()
            if (end - start < Clip.MIN_LENGTH_MS) {
                if (start != clip.trimStartMs) start = end - Clip.MIN_LENGTH_MS else end = start + Clip.MIN_LENGTH_MS
            }
            val s = start.coerceIn(0, clip.info.durationMs - Clip.MIN_LENGTH_MS)
            val e = end.coerceIn(s + Clip.MIN_LENGTH_MS, clip.info.durationMs)
            state.updateClip(clip.id, live = true) { it.copy(trimStartMs = s, trimEndMs = e) }
        },
        onValueChangeFinished = state::settle,
        valueRange = 0f..full,
    )
}

@Composable
private fun SpeedPanel(clip: Clip, state: EditorState) {
    LabeledSlider(
        label = "Speed",
        value = clip.speed,
        range = 0.25f..4f,
        display = { String.format(Locale.US, "%.2f×", it) },
        onChange = { v ->
            val snapped = (v * 20).roundToInt() / 20f
            state.updateClip(clip.id, live = true) { it.copy(speed = snapped) }
        },
        onDone = state::settle,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (s in listOf(0.5f, 1f, 1.5f, 2f, 3f)) {
            FilterChip(
                selected = clip.speed == s,
                onClick = { state.updateClip(clip.id) { it.copy(speed = s) } },
                label = { Text("${if (s % 1f == 0f) s.toInt() else s}×") },
            )
        }
    }
    Text(
        "New length ${formatSeconds(clip.outputDurationMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VolumePanel(clip: Clip, state: EditorState) {
    LabeledSlider(
        label = "Volume",
        value = clip.volume,
        range = 0f..2f,
        display = { "${(it * 100).roundToInt()}%" },
        onChange = { v -> state.updateClip(clip.id, live = true) { it.copy(volume = (v * 20).roundToInt() / 20f) } },
        onDone = state::settle,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { state.updateClip(clip.id) { it.copy(volume = 0f) } }, label = { Text("Mute") })
        AssistChip(onClick = { state.updateClip(clip.id) { it.copy(volume = 1f) } }, label = { Text("100%") })
        AssistChip(onClick = { state.updateAllClips { it.copy(volume = clip.volume) } }, label = { Text("Apply to all") })
    }
}

@Composable
private fun FilterPanel(clip: Clip, state: EditorState) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items(FilterPreset.entries) { preset ->
            val selected = clip.filter == preset
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable { state.updateClip(clip.id) { it.copy(filter = preset) } },
            ) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(swatch(preset))
                        .border(
                            if (selected) 2.dp else 0.dp,
                            if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            RoundedCornerShape(10.dp),
                        ),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    preset.label, fontSize = 11.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    Spacer(Modifier.height(6.dp))
    AssistChip(onClick = { state.updateAllClips { it.copy(filter = clip.filter) } }, label = { Text("Apply to all clips") })
}

/** A rough colour cue for each look; real previews come from the live player above. */
private fun swatch(preset: FilterPreset): androidx.compose.ui.graphics.Brush {
    val colors = when (preset) {
        FilterPreset.NONE -> listOf(Color(0xFF4F7CAC), Color(0xFFE9B872))
        FilterPreset.VIVID -> listOf(Color(0xFF0091FF), Color(0xFFFF3D7F))
        FilterPreset.WARM -> listOf(Color(0xFFFFA351), Color(0xFFFFD27F))
        FilterPreset.COOL -> listOf(Color(0xFF4FC3F7), Color(0xFF8C9EFF))
        FilterPreset.VINTAGE -> listOf(Color(0xFF8D6E63), Color(0xFFD7B98E))
        FilterPreset.FADE -> listOf(Color(0xFF9FA8B5), Color(0xFFD9D4CC))
        FilterPreset.DRAMA -> listOf(Color(0xFF263238), Color(0xFF90A4AE))
        FilterPreset.MONO -> listOf(Color(0xFF555555), Color(0xFFCCCCCC))
        FilterPreset.NOIR -> listOf(Color(0xFF000000), Color(0xFF9E9E9E))
        FilterPreset.INVERT -> listOf(Color(0xFFB0835C), Color(0xFF1647A3))
    }
    return androidx.compose.ui.graphics.Brush.linearGradient(colors)
}

@Composable
private fun AdjustPanel(clip: Clip, state: EditorState) {
    val a = clip.adjust
    fun set(change: (Adjustments) -> Adjustments) =
        state.updateClip(clip.id, live = true) { it.copy(adjust = change(it.adjust)) }

    LabeledSlider("Brightness", a.brightness, -0.6f..0.6f, { pct(it / 0.6f) }, { v -> set { it.copy(brightness = v) } }, state::settle)
    LabeledSlider("Contrast", a.contrast, -0.8f..0.8f, { pct(it / 0.8f) }, { v -> set { it.copy(contrast = v) } }, state::settle)
    LabeledSlider("Saturation", a.saturation, -100f..100f, { pct(it / 100f) }, { v -> set { it.copy(saturation = v) } }, state::settle)
    LabeledSlider("Hue", a.hue, -180f..180f, { "${it.roundToInt()}°" }, { v -> set { it.copy(hue = v) } }, state::settle)
    LabeledSlider("Blur", a.blur, 0f..12f, { if (it < 0.1f) "Off" else String.format(Locale.US, "%.1f", it) }, { v -> set { it.copy(blur = if (v < 0.1f) 0f else v) } }, state::settle)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = { state.updateClip(clip.id) { it.copy(adjust = Adjustments()) } }, label = { Text("Reset") })
        AssistChip(onClick = { state.updateAllClips { it.copy(adjust = a) } }, label = { Text("Apply to all") })
    }
}

private fun pct(f: Float) = "${if (f > 0) "+" else ""}${(f * 100).roundToInt()}"

@Composable
private fun TextPanel(clip: Clip, state: EditorState, onEditText: (TextLayer) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        item {
            AssistChip(
                onClick = { onEditText(TextLayer(state.newId(), "")) },
                label = { Text("Add text") },
                leadingIcon = { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)) },
            )
        }
        items(clip.texts, key = { it.id }) { layer ->
            AssistChip(
                onClick = { onEditText(layer) },
                label = { Text(layer.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, null, Modifier.size(18.dp)) },
            )
        }
    }
    if (clip.texts.isNotEmpty()) {
        Text(
            "Text shows for the whole clip. Split the clip to time it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AudioPanel(state: EditorState, onPickMusic: () -> Unit) {
    val project = state.project
    val music = project.music
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Mute original audio", modifier = Modifier.weight(1f))
        Switch(checked = project.muteOriginal, onCheckedChange = { state.commit(project.copy(muteOriginal = it)) })
    }
    if (music == null) {
        AssistChip(
            onClick = onPickMusic,
            label = { Text("Add music") },
            leadingIcon = { Icon(Icons.Rounded.MusicNote, null, Modifier.size(18.dp)) },
        )
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(music.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            TextButton(onClick = onPickMusic) { Text("Replace") }
            TextButton(onClick = { state.commit(project.copy(music = null)) }) { Text("Remove") }
        }
        LabeledSlider(
            "Music volume", music.volume, 0f..1.5f, { "${(it * 100).roundToInt()}%" },
            { v -> state.live(state.project.copy(music = music.copy(volume = (v * 20).roundToInt() / 20f))) },
            state::settle,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = music.fadeIn,
                onClick = { state.commit(project.copy(music = music.copy(fadeIn = !music.fadeIn))) },
                label = { Text("Fade in") },
            )
            FilterChip(
                selected = music.fadeOut,
                onClick = { state.commit(project.copy(music = music.copy(fadeOut = !music.fadeOut))) },
                label = { Text("Fade out") },
            )
        }
        if (music.durationMs < project.durationMs) {
            Text(
                "Music is shorter than the video, so it loops.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CanvasPanel(state: EditorState) {
    val project = state.project
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(CanvasRatio.entries) { ratio ->
            FilterChip(
                selected = project.canvas == ratio,
                onClick = { state.commit(project.copy(canvas = ratio)) },
                label = { Text(ratio.label) },
            )
        }
    }
    Text(
        "Clips are fitted inside the frame; bars fill the rest.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: (Float) -> String,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(84.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = onDone,
            valueRange = range,
            modifier = Modifier.weight(1f).height(36.dp),
        )
        Text(
            display(value), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(52.dp).padding(start = 8.dp),
        )
    }
}

private val TEXT_COLORS = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFFFD600, 0xFFFF5252, 0xFFFF7A45,
    0xFF69F0AE, 0xFF40C4FF, 0xFF7C4DFF, 0xFFFF4081,
).map { it.toInt() }

@Composable
fun TextEditorDialog(initial: TextLayer, onDismiss: () -> Unit, onSave: (TextLayer) -> Unit) {
    var layer by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.text.isEmpty()) "Add text" else "Edit text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = layer.text,
                    onValueChange = { layer = layer.copy(text = it) },
                    placeholder = { Text("Enter text") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(TEXT_COLORS) { c ->
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(
                                    if (layer.color == c) 3.dp else 1.dp,
                                    if (layer.color == c) MaterialTheme.colorScheme.primary else Color.Gray,
                                    CircleShape,
                                )
                                .clickable { layer = layer.copy(color = c) },
                        )
                    }
                }
                LabeledSlider(
                    "Size", layer.size, 0.03f..0.16f, { "${(it * 100).roundToInt()}" },
                    { layer = layer.copy(size = it) }, {},
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextPlacement.entries.forEach { p ->
                        FilterChip(
                            selected = layer.placement == p,
                            onClick = { layer = layer.copy(placement = p) },
                            label = { Text(p.label) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Background box", modifier = Modifier.weight(1f))
                    Switch(checked = layer.background, onCheckedChange = { layer = layer.copy(background = it) })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(layer) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (initial.text.isNotEmpty()) {
                    TextButton(onClick = { onSave(layer.copy(text = "")) }) { Text("Delete") }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
