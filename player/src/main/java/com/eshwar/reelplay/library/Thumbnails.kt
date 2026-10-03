package com.eshwar.reelplay.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Video and image thumbnails, cached in memory. Decoding a frame is expensive, so at most a few
 * run at once — enough to fill a screen quickly without starving playback of the decoder.
 */
object Thumbnails {

    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val gate = Semaphore(3)

    fun cached(key: String): Bitmap? = cache.get(key)

    /** The system's own thumbnail for a library item: fast, and usually already on disk. */
    suspend fun forUri(context: Context, uri: Uri): Bitmap? {
        val key = uri.toString()
        cache.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                val bmp = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(uri, Size(384, 216), null)
                    } else {
                        frameAt(context, uri, 1_000_000L, 384)
                    }
                } catch (_: Exception) {
                    frameAt(context, uri, 1_000_000L, 384)
                }
                bmp?.also { cache.put(key, it) }
            }
        }
    }

    /** One frame at [timeUs], scaled so its long side is about [maxSide] pixels. */
    suspend fun frame(context: Context, uri: Uri, timeUs: Long, maxSide: Int = 200): Bitmap? {
        val key = "$uri@$timeUs/$maxSide"
        cache.get(key)?.let { return it }
        return gate.withPermit {
            withContext(Dispatchers.IO) {
                frameAt(context, uri, timeUs, maxSide)?.also { cache.put(key, it) }
            }
        }
    }

    private fun frameAt(context: Context, uri: Uri, timeUs: Long, maxSide: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, maxSide, maxSide,
                )
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            raw ?: retriever.frameAtTime
        } catch (_: Exception) {
            decodeImage(context, uri, maxSide) // Photos on the editor timeline land here.
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }
}

private fun decodeImage(context: Context, uri: Uri, maxSide: Int): Bitmap? = try {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
} catch (_: Exception) {
    null
}

@Composable
fun VideoThumbnail(uri: Uri, modifier: Modifier = Modifier, timeUs: Long? = null) {
    val context = LocalContext.current
    val bitmap by produceState(
        initialValue = if (timeUs == null) Thumbnails.cached(uri.toString()) else null,
        uri, timeUs,
    ) {
        value = if (timeUs == null) Thumbnails.forUri(context, uri)
        else Thumbnails.frame(context, uri, timeUs)
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                Icons.Rounded.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
