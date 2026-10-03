package com.eshwar.reelplay.editor

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.core.graphics.withTranslation
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.DefaultGainProvider
import androidx.media3.common.audio.GainProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.CanvasOverlay
import androidx.media3.effect.Contrast
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.HslAdjustment
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbAdjustment
import androidx.media3.effect.RgbFilter
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import kotlin.math.min

/**
 * Turns a [Project] into a Media3 [Composition]. The same composition drives the live preview
 * (CompositionPlayer) and the export (Transformer), so what you see is what you get.
 */
@OptIn(UnstableApi::class)
object CompositionFactory {

    fun build(project: Project, shortSide: Int = project.resolution): Composition {
        val (outW, outH) = project.outputSize(shortSide)
        val items = project.clips.map { clip -> editedItem(project, clip, outW, outH) }

        // Audio+video for every clip, so photos and silent clips get silence instead of
        // breaking the sequence's audio track.
        val main = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO))
            .addItems(items)
            .build()

        val sequences = mutableListOf(main)
        project.music?.let { music -> musicSequence(music, project.durationMs)?.let(sequences::add) }

        val builder = Composition.Builder(sequences)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // HDR phone footage plus SDR filters and overlays: tone-map once, up front.
            builder.setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
        }
        return builder.build()
    }

    private fun editedItem(project: Project, clip: Clip, outW: Int, outH: Int): EditedMediaItem {
        val video = ArrayList<Effect>()

        if (clip.rotation != 0 || clip.flipH || clip.flipV) {
            video += ScaleAndRotateTransformation.Builder()
                .setScale(if (clip.flipH) -1f else 1f, if (clip.flipV) -1f else 1f)
                // Media3 rotates counter-clockwise; the UI talks clockwise.
                .setRotationDegrees(((360 - clip.rotation) % 360).toFloat())
                .build()
        }
        video += filterEffects(clip.filter)
        video += adjustEffects(clip.adjust)
        // Fit every clip into the same canvas, letterboxing as needed.
        video += Presentation.createForWidthAndHeight(outW, outH, Presentation.LAYOUT_SCALE_TO_FIT)
        if (clip.texts.isNotEmpty()) {
            video += OverlayEffect(listOf(TextCanvasOverlay(clip.texts)))
        }

        val audio = ArrayList<AudioProcessor>()
        val gain = if (project.muteOriginal) 0f else clip.volume
        if (gain != 1f) audio += GainProcessor(DefaultGainProvider.Builder(gain).build())

        return if (clip.isImage) {
            val mediaItem = MediaItem.Builder()
                .setUri(clip.uri)
                .setImageDurationMs(clip.sourceSpanMs)
                .build()
            EditedMediaItem.Builder(mediaItem)
                .setDurationUs(clip.sourceSpanMs * 1000)
                .setFrameRate(30)
                .setEffects(Effects(audio, video))
                .build()
        } else {
            val mediaItem = MediaItem.Builder()
                .setUri(clip.uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clip.trimStartMs)
                        .setEndPositionMs(clip.trimEndMs)
                        .build(),
                )
                .build()
            val builder = EditedMediaItem.Builder(mediaItem)
                .setDurationUs(clip.info.durationMs * 1000)
                .setEffects(Effects(audio, video))
            if (clip.speed != 1f) builder.setSpeed(ConstantSpeed(clip.speed))
            builder.build()
        }
    }

    private fun musicSequence(music: Music, totalMs: Long): EditedMediaItemSequence? {
        if (totalMs <= 0 || music.durationMs <= 0) return null
        val loops = music.durationMs < totalMs
        val lengthMs = min(music.durationMs, totalMs)
        val fadeMs = min(1_500L, lengthMs / 3)

        val gain = DefaultGainProvider.Builder(music.volume)
        if (music.fadeIn && fadeMs > 0) {
            gain.addFadeAt(0, fadeMs * 1000, DefaultGainProvider.FADE_IN_LINEAR)
        }
        if (music.fadeOut && !loops && fadeMs > 0) {
            gain.addFadeAt((lengthMs - fadeMs) * 1000, fadeMs * 1000, DefaultGainProvider.FADE_OUT_LINEAR)
        }

        val mediaItem = MediaItem.Builder()
            .setUri(music.uri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder().setEndPositionMs(lengthMs).build(),
            )
            .build()
        val item = EditedMediaItem.Builder(mediaItem)
            .setDurationUs(music.durationMs * 1000)
            .setEffects(Effects(listOf(GainProcessor(gain.build())), emptyList()))
            .build()
        return EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
            .addItem(item)
            .setIsLooping(loops)
            .build()
    }

    fun filterEffects(filter: FilterPreset): List<Effect> = when (filter) {
        FilterPreset.NONE -> emptyList()
        FilterPreset.VIVID -> listOf(HslAdjustment.Builder().adjustSaturation(35f).build(), Contrast(0.12f))
        FilterPreset.WARM -> listOf(
            RgbAdjustment.Builder().setRedScale(1.12f).setGreenScale(1.03f).setBlueScale(0.85f).build(),
        )
        FilterPreset.COOL -> listOf(
            RgbAdjustment.Builder().setRedScale(0.88f).setGreenScale(1.0f).setBlueScale(1.15f).build(),
        )
        FilterPreset.VINTAGE -> listOf(SEPIA, Contrast(-0.08f))
        FilterPreset.FADE -> listOf(Contrast(-0.25f), HslAdjustment.Builder().adjustSaturation(-30f).build(), Brightness(0.06f))
        FilterPreset.DRAMA -> listOf(Contrast(0.35f), HslAdjustment.Builder().adjustSaturation(-25f).build())
        FilterPreset.MONO -> listOf(RgbFilter.createGrayscaleFilter())
        FilterPreset.NOIR -> listOf(RgbFilter.createGrayscaleFilter(), Contrast(0.4f))
        FilterPreset.INVERT -> listOf(RgbFilter.createInvertedFilter())
    }

    private fun adjustEffects(a: Adjustments): List<Effect> = buildList {
        if (a.brightness != 0f) add(Brightness(a.brightness.coerceIn(-1f, 1f)))
        if (a.contrast != 0f) add(Contrast(a.contrast.coerceIn(-1f, 1f)))
        if (a.saturation != 0f || a.hue != 0f) {
            add(HslAdjustment.Builder().adjustSaturation(a.saturation).adjustHue(a.hue).build())
        }
        if (a.blur > 0f) add(GaussianBlur(a.blur))
    }

    /** Classic sepia, as a column-major 4x4 RGBA matrix. */
    private val SEPIA = RgbMatrix { _, _ ->
        floatArrayOf(
            0.393f, 0.349f, 0.272f, 0f,
            0.769f, 0.686f, 0.534f, 0f,
            0.189f, 0.168f, 0.131f, 0f,
            0f, 0f, 0f, 1f,
        )
    }
}

@OptIn(UnstableApi::class)
private class ConstantSpeed(private val speed: Float) : SpeedProvider {
    override fun getSpeed(timeUs: Long): Float = speed
    override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
}

/** Captions burned into the frame, laid out relative to the output canvas. */
@OptIn(UnstableApi::class)
private class TextCanvasOverlay(private val layers: List<TextLayer>) : CanvasOverlay(true) {

    private var drawnOn: Canvas? = null
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas, presentationTimeUs: Long) {
        // The captions don't change over the clip, so draw once per backing bitmap.
        if (drawnOn === canvas) return
        drawnOn = canvas
        canvas.drawColor(0, PorterDuff.Mode.CLEAR)
        layers.forEach { draw(canvas, it) }
    }

    private fun draw(canvas: Canvas, layer: TextLayer) {
        if (layer.text.isBlank()) return
        val w = canvas.width
        val h = canvas.height
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = layer.color
            textSize = layer.size * h
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (!layer.background) setShadowLayer(textSize * 0.08f, 0f, textSize * 0.04f, 0xCC000000.toInt())
        }
        val maxWidth = (w * 0.88f).toInt()
        val layout = StaticLayout.Builder.obtain(layer.text, 0, layer.text.length, paint, maxWidth)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        val top = (h * layer.placement.anchorY - layout.height / 2f)
            .coerceIn(h * 0.02f, h * 0.98f - layout.height)
        val left = (w - maxWidth) / 2f
        canvas.withTranslation(left, top) {
            if (layer.background) {
                var lineLeft = Float.MAX_VALUE
                var lineRight = 0f
                for (i in 0 until layout.lineCount) {
                    lineLeft = min(lineLeft, layout.getLineLeft(i))
                    lineRight = maxOf(lineRight, layout.getLineRight(i))
                }
                val pad = paint.textSize * 0.3f
                bgPaint.color = 0xB3000000.toInt()
                drawRoundRect(
                    RectF(lineLeft - pad, -pad * 0.6f, lineRight + pad, layout.height + pad * 0.6f),
                    pad, pad, bgPaint,
                )
            }
            layout.draw(this)
        }
    }
}
