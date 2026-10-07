package com.eshwar.reelplay.editor

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.core.content.FileProvider
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.VideoEncoderSettings
import android.media.MediaCodecInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Runs one Transformer export to a temp file, then publishes it to Movies/ReelPlay.
 * All calls happen on the main thread, which is where Transformer wants them.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class Exporter(private val context: Context) {

    sealed interface State {
        data object Idle : State
        data class Running(val progress: Int?) : State
        data class Done(val uri: Uri, val sizeBytes: Long) : State
        data class Failed(val message: String) : State
    }

    private var transformer: Transformer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var poller: Runnable? = null

    fun start(composition: Composition, quality: ExportQuality, onState: (State) -> Unit, onFinished: (File) -> Unit) {
        cancel()
        val out = File(context.cacheDir, "export-${System.currentTimeMillis()}.mp4")
        // Encoder at the original's quality (see ExportQuality), falling back gracefully on
        // phones whose encoder can't do the exact settings.
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(quality.videoBitrate)
                    .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                    .build(),
            )
            .setEnableFallback(true)
            .build()
        val t = Transformer.Builder(context)
            .setEncoderFactory(encoders)
            .setVideoMimeType(quality.videoMime)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            // A plain trim copies the video and only re-encodes the frames up to the first
            // keyframe after the cut, so it comes out identical to the original.
            .experimentalSetTrimOptimizationEnabled(true)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    stopPolling()
                    transformer = null
                    onFinished(out)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    stopPolling()
                    transformer = null
                    out.delete()
                    onState(State.Failed(exportException.message ?: exportException.errorCodeName))
                }
            })
            .build()
        transformer = t
        onState(State.Running(0))
        t.start(composition, out.absolutePath)

        val holder = ProgressHolder()
        poller = object : Runnable {
            override fun run() {
                val tr = transformer ?: return
                val state = tr.getProgress(holder)
                onState(State.Running(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null))
                handler.postDelayed(this, 250)
            }
        }.also { handler.post(it) }
    }

    fun cancel() {
        stopPolling()
        transformer?.cancel()
        transformer = null
    }

    private fun stopPolling() {
        poller?.let(handler::removeCallbacks)
        poller = null
    }

    companion object {
        /** Copies the finished file into the shared Movies collection so galleries see it. */
        suspend fun publish(context: Context, file: File): Uri = withContext(Dispatchers.IO) {
            val name = "ReelPlay_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = context.contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/ReelPlay")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                    val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val uri = resolver.insert(collection, values) ?: error("Couldn't create the video in Movies")
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Video.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                    uri
                } else {
                    @Suppress("DEPRECATION")
                    val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "ReelPlay")
                    dir.mkdirs()
                    val dest = File(dir, name)
                    file.copyTo(dest, overwrite = true)
                    android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
                    Uri.fromFile(dest)
                }
            } catch (e: Exception) {
                // No storage permission on old Android, or storage full: keep the private copy
                // and hand out a shareable link to it instead.
                val kept = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir, name)
                file.copyTo(kept, overwrite = true)
                FileProvider.getUriForFile(context, context.packageName + ".files", kept)
            } finally {
                file.delete()
            }
        }
    }
}
