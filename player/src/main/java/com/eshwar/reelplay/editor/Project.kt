package com.eshwar.reelplay.editor

import android.net.Uri
import kotlin.math.roundToInt

/** What a probe of a picked file found. Width and height are as displayed, rotation applied. */
data class MediaInfo(
    val name: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val hasAudio: Boolean,
    val isImage: Boolean,
)

enum class FilterPreset(val label: String) {
    NONE("Original"),
    VIVID("Vivid"),
    WARM("Warm"),
    COOL("Cool"),
    VINTAGE("Vintage"),
    FADE("Fade"),
    DRAMA("Drama"),
    MONO("B&W"),
    NOIR("Noir"),
    INVERT("Invert"),
}

data class Adjustments(
    /** -1..1 */
    val brightness: Float = 0f,
    /** -1..1 */
    val contrast: Float = 0f,
    /** -100..100 */
    val saturation: Float = 0f,
    /** -180..180 degrees */
    val hue: Float = 0f,
    /** Gaussian sigma in pixels, 0 = off */
    val blur: Float = 0f,
) {
    val isDefault get() = this == Adjustments()
}

enum class TextPlacement(val label: String, val anchorY: Float) {
    TOP("Top", 0.12f),
    CENTER("Middle", 0.5f),
    BOTTOM("Bottom", 0.86f),
}

data class TextLayer(
    val id: Long,
    val text: String,
    val color: Int = 0xFFFFFFFF.toInt(),
    /** Text height as a fraction of the frame's height. */
    val size: Float = 0.07f,
    val placement: TextPlacement = TextPlacement.BOTTOM,
    val background: Boolean = false,
)

data class Clip(
    val id: Long,
    val uri: Uri,
    val info: MediaInfo,
    val trimStartMs: Long,
    val trimEndMs: Long,
    val speed: Float = 1f,
    val volume: Float = 1f,
    /** Clockwise, multiples of 90. */
    val rotation: Int = 0,
    val flipH: Boolean = false,
    val flipV: Boolean = false,
    val filter: FilterPreset = FilterPreset.NONE,
    val adjust: Adjustments = Adjustments(),
    val texts: List<TextLayer> = emptyList(),
) {
    val isImage get() = info.isImage

    /** Length of the source range this clip uses. */
    val sourceSpanMs get() = trimEndMs - trimStartMs

    /** Length on the timeline, after speed. */
    val outputDurationMs: Long
        get() = if (isImage) sourceSpanMs else (sourceSpanMs / speed).toLong()

    /** Display aspect ratio after this clip's own rotation. */
    val aspect: Float
        get() {
            val w = info.width.coerceAtLeast(1).toFloat()
            val h = info.height.coerceAtLeast(1).toFloat()
            return if (rotation % 180 == 0) w / h else h / w
        }

    companion object {
        const val MIN_LENGTH_MS = 300L
        const val DEFAULT_IMAGE_MS = 3_000L
    }
}

enum class CanvasRatio(val label: String, val ratio: Float?) {
    ORIGINAL("Original", null),
    PORTRAIT_9_16("9:16", 9f / 16f),
    SQUARE("1:1", 1f),
    LANDSCAPE_16_9("16:9", 16f / 9f),
    PORTRAIT_4_5("4:5", 4f / 5f),
    LANDSCAPE_4_3("4:3", 4f / 3f),
    CINEMA("2.35:1", 2.35f),
}

data class Music(
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val volume: Float = 0.8f,
    val fadeIn: Boolean = true,
    val fadeOut: Boolean = true,
)

data class Project(
    val clips: List<Clip> = emptyList(),
    val canvas: CanvasRatio = CanvasRatio.ORIGINAL,
    val music: Music? = null,
    val muteOriginal: Boolean = false,
    /** Short side of the exported frame, in pixels. */
    val resolution: Int = 1080,
) {
    val durationMs: Long get() = clips.sumOf { it.outputDurationMs }

    /** Timeline start of each clip. */
    fun clipStarts(): List<Long> {
        var t = 0L
        return clips.map { c -> t.also { t += c.outputDurationMs } }
    }

    /** The clip under timeline position [ms], and how far into that clip (in output time) it is. */
    fun locate(ms: Long): Pair<Int, Long>? {
        var t = 0L
        clips.forEachIndexed { i, c ->
            if (ms < t + c.outputDurationMs || i == clips.lastIndex) return i to (ms - t).coerceIn(0, c.outputDurationMs)
            t += c.outputDurationMs
        }
        return null
    }

    val aspect: Float
        get() = canvas.ratio ?: clips.firstOrNull()?.aspect ?: (9f / 16f)

    /** Output frame size for a given short side, rounded to even numbers as encoders need. */
    fun outputSize(shortSide: Int = resolution): Pair<Int, Int> {
        val a = aspect
        return if (a >= 1f) even(shortSide * a) to even(shortSide.toFloat())
        else even(shortSide.toFloat()) to even(shortSide / a)
    }

    private fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)
}
