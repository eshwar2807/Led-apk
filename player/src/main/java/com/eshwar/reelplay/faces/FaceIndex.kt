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
    /** For each item (by key), the face in it that made it this person's. */
    val faceIn: Map<String, Long> = emptyMap(),
) {
    val photos: Int get() = items.count { !it.isVideo }
    val videos: Int get() = items.count { it.isVideo }
}

data class ScanState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    /** Why the last scan stopped or skipped things, in words for the user; null if all went well. */
    val error: String? = null,
)

/** What the index holds so far. */
data class IndexStats(val photos: Int = 0, val videos: Int = 0, val photoFaces: Int = 0, val videoFaces: Int = 0)

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

    /**
     * Looks at every photo and video that's new or changed since last time. Safe to call from
     * several places at once (the People screen while it's open, the background worker): they
     * take turns, and whoever comes second finds nothing left to do.
     */
    suspend fun scan(context: Context) = lock.withLock {
        withContext(Dispatchers.Default) {
            if (!Prefs.faceGrouping) return@withContext
            _state.value = ScanState(running = true)
            var error: String? = null
            var done = 0
            var total = 0
            try {
                val db = store(context)
                val onPhone = (photos(context) + videos(context)).associateBy { it.key }
                val indexed = db.indexed()
                db.remove(indexed.keys - onPhone.keys)
                val todo = onPhone.values.filter { indexed[it.key] != it.modified }.sortedByDescending { it.takenAt }
                total = todo.size
                Log.i(TAG, "scan: ${onPhone.size} on the phone, ${indexed.size} indexed, ${todo.size} to look at")
                if (todo.isEmpty()) return@withContext
                _state.value = ScanState(true, 0, total)
                val engine = try {
                    FaceEngine(context)
                } catch (e: Throwable) {
                    Log.e(TAG, "face engine unavailable", e)
                    error = "The face finder couldn't start on this phone (${describe(e)})."
                    return@withContext
                }
                var failed = 0
                try {
                    for (item in todo) {
                        if (!currentCoroutineContext().isActive || !Prefs.faceGrouping) break
                        try {
                            val faces = if (item.isVideo) videoFaces(context, engine, item) else photoFaces(context, engine, item)
                            val kept = FaceMath.dedupe(faces.map { it.embedding }, faces.map { it.quality }).map { faces[it] }
                            db.put(item, kept.take(MAX_FACES_PER_ITEM))
                        } catch (e: Exception) {
                            // Not recorded, so it's tried again on the next scan.
                            failed++
                            Log.w(TAG, "couldn't look at ${item.key} (${item.name})", e)
                            error = "Couldn't look at $failed of them (${describe(e)}). They'll be tried again."
                            if (failed >= 20 && failed == done + 1) {
                                // Nothing has worked at all: stop rather than grind through every item.
                                error = "Faces can't be searched on this phone right now (${describe(e)})."
                                break
                            }
                        }
                        done++
                        _state.value = ScanState(true, done, total, error)
                    }
                } finally {
                    engine.close()
                }
                Log.i(TAG, "scan: looked at $done of $total, $failed failed")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e(TAG, "scan failed", e)
                error = "Searching stopped: ${describe(e)}"
            } finally {
                _state.value = ScanState(false, done, total, error)
            }
        }
    }

    private fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() }?.take(120) ?: e.javaClass.simpleName

    suspend fun stats(context: Context): IndexStats = withContext(Dispatchers.IO) {
        if (!Prefs.faceGrouping) return@withContext IndexStats()
        runCatching { store(context).stats() }.getOrNull() ?: IndexStats()
    }

    /** The library's average face as of the last [people], for matching names to people. */
    @Volatile private var lastMean: FloatArray? = null

    /** Everyone found so far, most photographed first. */
    suspend fun people(context: Context): List<Person> = withContext(Dispatchers.Default) {
        if (!Prefs.faceGrouping) return@withContext emptyList()
        val db = store(context)
        val faces = db.faces()
        if (faces.isEmpty()) return@withContext emptyList()
        val media = db.media()
        val labels = db.labels()
        val (clusters, mean) = FaceMath.people(faces.map { it.embedding }, faces.map { it.quality })
        lastMean = mean
        val centred = faces.map { FaceMath.centred(it.embedding, mean) }
        val labelCentres = labels.map { FaceMath.centred(it.centre, mean) }
        val groups = clusters.map { group ->
            val name = labelFor(FaceMath.centre(group.map { centred[it] }), labelCentres)?.let { labels[it].name }
            name to group
        }
        // Groups given the same name are one person: that's how a person split in two is joined up.
        val byName = groups.filter { it.first != null }.groupBy({ it.first }, { it.second })
            .map { (name, gs) -> name to gs.flatten() }
        (byName + groups.filter { it.first == null })
            .mapNotNull { (name, group) ->
                val members = group.map { faces[it] }
                val items = members.mapNotNull { media[it.mediaKey] }.distinctBy { it.key }
                if (items.size < MIN_ITEMS && name == null) return@mapNotNull null
                Person(
                    coverFaceId = members.maxBy { it.quality }.id,
                    name = name,
                    centre = FaceMath.centre(members.map { it.embedding }),
                    items = items.sortedByDescending { it.takenAt },
                    faceIn = members.groupBy { it.mediaKey }.mapValues { (_, fs) -> fs.maxBy { it.quality }.id },
                )
            }
            .sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.items.size })
    }

    /** The index of the saved name whose face is closest to [centre], if close enough. */
    private fun labelFor(centre: FloatArray, labelCentres: List<FloatArray>): Int? =
        labelCentres.indices.filter { FaceMath.dot(labelCentres[it], centre) > FaceStore.LABEL_MATCH }
            .maxByOrNull { FaceMath.dot(labelCentres[it], centre) }

    suspend fun faceCrop(context: Context, faceId: Long): Bitmap? = withContext(Dispatchers.IO) {
        if (!Prefs.faceGrouping) return@withContext null
        store(context).crop(faceId)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    /**
     * Names [person]. Giving another group the same name joins the two; a blank name removes it.
     * A merged person can carry several saved faces under one name; renaming replaces them all.
     */
    /** Forgets photos and videos the user just deleted, so they leave every list at once. */
    suspend fun forgetMedia(context: Context, keys: Collection<String>) = withContext(Dispatchers.IO) {
        store(context).remove(keys)
    }

    /** "Not this person": takes [faceId] out of whoever it was grouped with. */
    suspend fun hideFace(context: Context, faceId: Long) = withContext(Dispatchers.IO) {
        store(context).hide(faceId)
    }

    suspend fun rename(context: Context, person: Person, name: String) = withContext(Dispatchers.IO) {
        val db = store(context)
        val labels = db.labels()
        val mean = lastMean
        val replacing = when {
            person.name != null -> labels.filter { it.name == person.name }.map { it.id }
            mean != null -> labelFor(FaceMath.centred(person.centre, mean), labels.map { FaceMath.centred(it.centre, mean) })
                ?.let { listOf(labels[it].id) }.orEmpty()
            else -> emptyList()
        }
        if (person.name != null && name.isNotBlank()) {
            // Keep each saved face (one per joined group) and just change the name.
            val kept = labels.filter { it.id in replacing }
            db.setLabel(replacing, person.centre, "")
            for (l in kept) db.setLabel(emptyList(), l.centre, name)
        } else {
            db.setLabel(replacing, person.centre, name)
        }
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
        // Not "no faces": an error, so it's reported and tried again next time.
        val bmp = decodePhoto(context, item.uri) ?: throw java.io.IOException("couldn't open the photo")
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

    /** The photo, upright and scaled so its long side is about [side]. */
    internal fun decodePhoto(context: Context, uri: Uri, side: Int = PHOTO_SIDE): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val long = maxOf(info.size.width, info.size.height)
                    if (long > side) {
                        val scale = side.toFloat() / long
                        decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                }
            } catch (e: Exception) {
                // Some phones' decoders choke on some files; the older decoder below may not.
                Log.w(TAG, "ImageDecoder failed for $uri", e)
            }
        }
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= side) sample *= 2
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
