package com.eshwar.torchglow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eshwar.torchglow.torch.TorchController
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

enum class TorchMode { OFF, STEADY, STROBE, SOS }

private data class Preset(val label: String, val hue: Float, val saturation: Float)

private val PRESETS = listOf(
    Preset("White", 0f, 0f),
    Preset("Warm", 34f, 0.35f),
    Preset("Red", 0f, 1f),
    Preset("Amber", 38f, 1f),
    Preset("Green", 122f, 1f),
    Preset("Cyan", 186f, 1f),
    Preset("Blue", 222f, 1f),
    Preset("Violet", 274f, 1f),
    Preset("Pink", 328f, 1f),
)

/** Morse SOS in units of [SOS_UNIT_MS]: dot dot dot, dash dash dash, dot dot dot. */
private val SOS_PATTERN = listOf(
    1, 1, 1, 1, 1, 3, // . . .
    3, 1, 3, 1, 3, 3, // - - -
    1, 1, 1, 1, 1, 7, // . . . + word gap
)
private const val SOS_UNIT_MS = 180L

@Composable
fun TorchGlowApp(controller: TorchController) {
    var mode by remember { mutableStateOf(TorchMode.OFF) }
    var strength by remember { mutableFloatStateOf(controller.defaultStrengthLevel.toFloat()) }
    var strobeHz by remember { mutableFloatStateOf(6f) }

    var hue by rememberSaveable { mutableFloatStateOf(210f) }
    var saturation by rememberSaveable { mutableFloatStateOf(1f) }
    var brightness by rememberSaveable { mutableFloatStateOf(1f) }
    var lampOpen by rememberSaveable { mutableStateOf(false) }

    val color = Color.hsv(hue, saturation, brightness)

    // Drives the flash unit. Restarting on any of these keys is cheap and keeps
    // the hardware in step with whatever the UI currently shows.
    LaunchedEffect(mode, strength, strobeHz) {
        val level = strength.roundToInt()
        when (mode) {
            TorchMode.OFF -> controller.turnOff()
            TorchMode.STEADY -> controller.setTorch(on = true, level = level)
            TorchMode.STROBE -> {
                val period = (1000f / strobeHz).toLong().coerceAtLeast(40L)
                val onMs = (period / 2).coerceAtLeast(20L)
                while (coroutineContext.isActive) {
                    controller.setTorch(on = true, level = level)
                    delay(onMs)
                    controller.setTorch(on = false)
                    delay(period - onMs)
                }
            }
            TorchMode.SOS -> {
                while (coroutineContext.isActive) {
                    SOS_PATTERN.forEachIndexed { index, units ->
                        controller.setTorch(on = index % 2 == 0, level = level)
                        delay(SOS_UNIT_MS * units)
                    }
                }
            }
        }
    }

    // The flash is shared with the rest of the system: if a quick-settings tile
    // or another app kills it, drop back to OFF instead of lying in the UI.
    val currentMode by rememberUpdatedState(mode)
    DisposableEffect(controller) {
        val callback = controller.registerTorchCallback { enabled ->
            if (!enabled && currentMode == TorchMode.STEADY) mode = TorchMode.OFF
        }
        onDispose { controller.unregisterTorchCallback(callback) }
    }

    if (lampOpen) {
        ScreenLight(color = color, onExit = { lampOpen = false })
        return
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                text = "Torch Glow",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )

            TorchCard(
                available = controller.isAvailable,
                mode = mode,
                onModeChange = { mode = it },
                supportsStrength = controller.supportsStrength,
                strength = strength,
                maxStrength = controller.maxStrengthLevel,
                onStrengthChange = { strength = it },
                strobeHz = strobeHz,
                onStrobeHzChange = { strobeHz = it },
            )

            ColorCard(
                hue = hue,
                saturation = saturation,
                brightness = brightness,
                color = color,
                onColorChange = { h, s -> hue = h; saturation = s },
                onBrightnessChange = { brightness = it },
                onOpenLamp = { lampOpen = true },
            )

            Text(
                text = "The flash LED itself is a white-only emitter — no phone can tint it. " +
                    "Colours from the wheel drive the full-screen lamp, which is what " +
                    "actually throws coloured light.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TorchCard(
    available: Boolean,
    mode: TorchMode,
    onModeChange: (TorchMode) -> Unit,
    supportsStrength: Boolean,
    strength: Float,
    maxStrength: Int,
    onStrengthChange: (Float) -> Unit,
    strobeHz: Float,
    onStrobeHzChange: (Float) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Flashlight",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            PowerButton(
                on = mode != TorchMode.OFF,
                enabled = available,
                onClick = {
                    onModeChange(if (mode == TorchMode.OFF) TorchMode.STEADY else TorchMode.OFF)
                },
            )

            Text(
                text = when {
                    !available -> "No flash unit found on this device"
                    mode == TorchMode.OFF -> "Tap to switch the torch on"
                    else -> "Torch on — ${mode.name.lowercase()}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                listOf(TorchMode.STEADY, TorchMode.STROBE, TorchMode.SOS).forEach { option ->
                    FilterChip(
                        selected = mode == option,
                        enabled = available,
                        onClick = {
                            onModeChange(if (mode == option) TorchMode.OFF else option)
                        },
                        label = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    )
                }
            }

            if (supportsStrength) {
                LabelledSlider(
                    label = "Intensity",
                    valueText = "${strength.roundToInt()} / $maxStrength",
                    value = strength,
                    valueRange = 1f..maxStrength.toFloat(),
                    steps = (maxStrength - 2).coerceAtLeast(0),
                    enabled = available,
                    onValueChange = onStrengthChange,
                )
            } else if (available) {
                Text(
                    text = "This flash reports on/off only — no intensity steps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (mode == TorchMode.STROBE) {
                LabelledSlider(
                    label = "Strobe rate",
                    valueText = "${"%.1f".format(strobeHz)} Hz",
                    value = strobeHz,
                    valueRange = 0.5f..20f,
                    enabled = available,
                    onValueChange = onStrobeHzChange,
                )
                Text(
                    text = "Fast strobing can trigger photosensitive seizures — use with care.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun ColorCard(
    hue: Float,
    saturation: Float,
    brightness: Float,
    color: Color,
    onColorChange: (Float, Float) -> Unit,
    onBrightnessChange: (Float) -> Unit,
    onOpenLamp: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = "Colour", style = MaterialTheme.typography.titleMedium)

            ColorWheel(
                hue = hue,
                saturation = saturation,
                value = brightness,
                onColorChange = onColorChange,
                modifier = Modifier
                    .fillMaxWidth(0.82f)
                    .align(Alignment.CenterHorizontally),
            )

            LabelledSlider(
                label = "Brightness",
                valueText = "${(brightness * 100).roundToInt()}%",
                value = brightness,
                valueRange = 0.05f..1f,
                onValueChange = onBrightnessChange,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(color)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp)),
                )
                Column {
                    Text(
                        text = "#%06X".format(color.toArgb() and 0xFFFFFF),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "H ${hue.roundToInt()}°  S ${(saturation * 100).roundToInt()}%  " +
                            "V ${(brightness * 100).roundToInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PRESETS.forEach { preset ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .clip(CircleShape)
                            .background(Color.hsv(preset.hue, preset.saturation, 1f))
                            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            .clickable { onColorChange(preset.hue, preset.saturation) },
                    )
                }
            }

            FilledTonalButton(
                onClick = onOpenLamp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(text = "  Open full-screen lamp")
            }
        }
    }
}

@Composable
private fun PowerButton(on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val glow = if (on) {
        Brush.radialGradient(
            listOf(Color(0xFFFFE9A8), Color(0xFFFFC85C), Color(0xFF6B4E00)),
        )
    } else {
        Brush.radialGradient(
            listOf(Color(0xFF2A2637), Color(0xFF1B1826)),
        )
    }
    Box(
        modifier = Modifier
            .size(168.dp)
            .clip(CircleShape)
            .background(glow)
            .border(
                width = 2.dp,
                color = if (on) Color(0xFFFFD77A) else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (on) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff,
            contentDescription = if (on) "Turn torch off" else "Turn torch on",
            tint = if (on) Color(0xFF3A2A00) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(72.dp),
        )
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = valueText, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}
