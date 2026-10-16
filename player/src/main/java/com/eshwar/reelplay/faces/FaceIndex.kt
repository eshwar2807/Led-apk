package com.eshwar.reelplay.faces

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.eshwar.reelplay.settings.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Someone who appears in the user's photos and videos. */
data class Person(
    /** The face shown for them; also a handle that stays put while the scan carries on. */
    val coverFaceId: Long,
    val name: String?,
    val centre: FloatArray,
    /** Newest first. */
    val items: List<MediaRef>,
) {
    val photos: Int get() = items.count { !it.isVideo }
    val videos: Int get() = items.count { it.isVideo }
}

data class ScanState(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/**
 * Looks through the phone's photos and videos for faces and groups them into people. Opt-in
 * (Settings → People); everything stays on the phone.
 */
object FaceIndex {
    private const val TAG = "FaceIndex"
    private const val WORK = "face-scan"

    /** Someone has to be in at least this many photos/videos to be listed. */
    private const val MIN_ITEMS = 2

    private val _state = MutableStateFlow(ScanState())
    val state: StateFlow<ScanState> = _state.asStateFlow()
    private val lock = Mutex()
    @Volatile private var store: FaceStore? = null

    private fun store(context: Context): FaceStore =
        store ?: synchronized(this) { store ?: FaceStore(context).also { store = it } }

    /** Starts (or carries on) looking at new and changed photos and videos, in the background. */
    fun scanInBackground(context: Context) {
        if (!Prefs.faceGrouping) return
        val request = OneTimeWorkRequestBuilder<FaceScanWorker>()
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    /** Turns the feature off: stops scanning and deletes every bit of face data. */
    suspend fun forget(context: Context) = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK)
        lock.withLock {
            synchronized(this@FaceIndex) {
                store?.close()
                store = null
            }
            context.applicationContext.deleteDatabase(FaceStore.NAME)
            _state.value = ScanState()
        }
    }

    suspend fun scan(context: Context) = lock.withLock {
        withContext(Dispatchers.Default) {
            if (!Prefs.faceGrouping) return@withContext
            val db = store(context)
            val onPhone = (photos(context) + videos(context)).associateBy { it.key }
            val indexed = db.indexed()
            db.remove(indexed.keys - onPhone.keys)
            val todo = onPhone.values.filter { indexed[it.key] != it.modified }.sortedByDescending { it.takenAt }
            if (todo.isEmpty()) return@withContext
            _state.value = ScanState(true, 0, todo.size)
            val engine = try {
                FaceEngine(context)
            } catch (e: Throwable) {
                Log.w(TAG, "face engine unavailable", e)
                _state.value = ScanState()
                return@withContext
            }
            try {
                for ((i, item) in todo.withIndex()) {
                    if (!currentCoroutineContext().isActive || !Prefs.faceGrouping) break
                    val faces = try {
                        if (item.isVideo) videoFaces(context, engine, item) else photoFaces(context, engine, item)
                    } catch (e: Exception) {
                        Log.w(TAG, "couldn't read ${item.key}", e)
                        emptyList()
                    }
                    val kept = FaceMath.dedupe(faces.map { it.embedding }, faces.map { it.quality }).map { faces[it] }
                    db.put(item, kept.take(MAX_FACES_PER_ITEM))
                    _state.value = ScanState(true, i + 1, todo.size)
                }
            } finally {
                engine.close()
                _state.value = ScanState(false, _state.value.done, _state.value.total)
            }
        }
    }

    /** Everyone found so far, most photographed first. */
    suspend fun people(context: Context): List<Person> = withContext(Dispatchers.Default) {
        if (!Prefs.faceGrouping) return@withContext emptyList()
        val db = store(context)
        val faces = db.faces()
        val media = db.media()
        val labels = db.labels()
        FaceMath.cluster(faces.map { it.embedding }, faces.map { it.quality })
            .mapNotNull { group ->
                val members = group.map { faces[it] }
                val items = members.mapNotNull { media[it.mediaKey] }.distinctBy { it.key }
                if (items.size < MIN_ITEMS) return@mapNotNull null
                val centre = FaceMath.centre(members.map { it.embedding })
                val name = labels.filter { FaceMath.dot(it.second, centre) > FaceStore.LABEL_MATCH }
                    .maxByOrNull { FaceMath.dot(it.second, centre) }?.first
                Person(
                    coverFaceId = members.maxBy { it.quality }.id,
                    name = name,
                    centre = centre,
                    items = items.sortedByDescending { it.takenAt },
                )
            }
            .sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.items.size })
    }

    suspend fun faceCrop(context: Context, faceId: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (!Prefs.faceGrouping) return@withContext null
        store(context).crop(faceId)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    suspend fun rename(context: Context, person: Person, name: String) = withContext(Dispatchers.IO) {
        store(context).setLabel(person.centre, name)
    }

    // ---- Reading the phone's media ----

    private const val MAX_FACES_PER_ITEM = 12
    private const val PHOTO_SIDE = 1600
    private const val FRAME_SIDE = 1280

    private fun photos(context: Context): List<MediaRef> {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        return query(context, uri, isVideo = false)
    }

    private fun videos(context: Context): List<MediaRef> {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        return query(context, uri, isVideo = true)
    }

    private fun query(context: Context, collection: Uri, isVideo: Boolean): List<MediaRef> {
        val cols = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.DATE_MODIFIED); add(MediaStore.MediaColumns.DATE_ADDED)
            if (isVideo) add(MediaStore.Video.Media.DURATION)
        }.toTypedArray()
        val out = ArrayList<MediaRef>()
        try {
            context.contentResolver.query(collection, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += MediaRef(
                        key = (if (isVideo) "v:" else "p:") + id,
                        isVideo = isVideo,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(1) ?: "$id",
                        modified = c.getLong(2),
                        durationMs = if (isVideo) c.getLong(4) else 0L,
                        takenAt = c.getLong(3),
                    )
                }
            }
        } catch (_: SecurityException) {
            // No access to this kind of media: nothing of it to index.
        }
        return out
    }

    private fun photoFaces(context: Context, engine: FaceEngine, item: MediaRef): List<FoundFace> {
        val bmp = decodePhoto(context, item.uri) ?: return emptyList()
        return try { engine.faces(bmp) } finally { bmp.recycle() }
    }

    /** A few frames spread through the video: enough to catch who's in it without decoding it all. */
    private fun videoFaces(context: Context, engine: FaceEngine, item: MediaRef): List<FoundFace> {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, item.uri)
            val duration = item.durationMs.takeIf { it > 0 }
                ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val count = (duration / 20_000L).toInt().coerceIn(3, 10)
            val out = ArrayList<FoundFace>()
            for (i in 0 until count) {
                val t = duration * (2 * i + 1) / (2 * count)
                val frame = (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        r.getScaledFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, FRAME_SIDE, FRAME_SIDE)
                    } else {
                        r.getFrameAtTime(t * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    }
                    ) ?: continue
                try { out += engine.faces(frame, t) } finally { frame.recycle() }
            }
            out
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    /** The photo, upright and scaled so its long side is about [PHOTO_SIDE]. */
    private fun decodePhoto(context: Context, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return try {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val long = maxOf(info.size.width, info.size.height)
                    if (long > PHOTO_SIDE) {
                        val scale = PHOTO_SIDE.toFloat() / long
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }
            } catch (_: Exception) {
                null
            }
        }
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_SIDE) sample *= 2
            val bmp = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val degrees = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
            if (degrees == 0) bmp
            else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
                .also { if (it !== bmp) bmp.recycle() }
        } catch (_: Exception) {
            null
        }
    }
}

/** Runs [FaceIndex.scan] in the background; WorkManager picks it back up if it's cut short. */
class FaceScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Prefs.init(applicationContext)
        FaceIndex.scan(applicationContext)
        return Result.success()
    }
}
