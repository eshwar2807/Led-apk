package com.eshwar.reelplay.editor

import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads just enough about a picked file to put it on the timeline. */
object MediaProbe {

    suspend fun probe(context: Context, uri: Uri): MediaInfo? = withContext(Dispatchers.IO) {
        val name = displayName(context, uri)
        val type = context.contentResolver.getType(uri).orEmpty()
        if (type.startsWith("image/")) probeImage(context, uri, name) else probeVideo(context, uri, name)
    }

    /** Duration of an audio track, for background music. */
    suspend fun audioDuration(context: Context, uri: Uri): Long = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    fun displayName(context: Context, uri: Uri): String {
        try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "Clip"
    }

    private fun probeVideo(context: Context, uri: Uri, name: String): MediaInfo? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
            if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") return null
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) w = h.also { h = w }
            val hasAudio = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            MediaInfo(name, duration, w, h, hasAudio, isImage = false)
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    private fun probeImage(context: Context, uri: Uri, name: String): MediaInfo? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        var w = opts.outWidth
        var h = opts.outHeight
        val orientation = context.contentResolver.openInputStream(uri)?.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } ?: ExifInterface.ORIENTATION_NORMAL
        if (orientation == ExifInterface.ORIENTATION_ROTATE_90 || orientation == ExifInterface.ORIENTATION_ROTATE_270 ||
            orientation == ExifInterface.ORIENTATION_TRANSPOSE || orientation == ExifInterface.ORIENTATION_TRANSVERSE
        ) {
            w = h.also { h = w }
        }
        if (w <= 0 || h <= 0) null
        else MediaInfo(name, Clip.DEFAULT_IMAGE_MS, w, h, hasAudio = false, isImage = true)
    } catch (_: Exception) {
        null
    }
}
