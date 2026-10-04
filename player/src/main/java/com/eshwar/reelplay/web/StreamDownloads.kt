package com.eshwar.reelplay.web

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A video stream (HLS) being saved as one MP4. */
data class StreamDownload(
    val id: String,
    val title: String,
    val pageUrl: String?,
    val playlistUrl: String,
    val audioUrl: String?,
    val quality: String,
    val state: State = State.QUEUED,
    val donePieces: Int = 0,
    val totalPieces: Int = 0,
    val bytes: Long = 0,
    val error: String? = null,
    val savedUri: String? = null,
    val location: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
) {
    enum class State(val label: String) {
        QUEUED("Waiting"), DOWNLOADING("Downloading"), MERGING("Making the MP4"), SAVING("Saving to Downloads"),
        DONE("Done"), FAILED("Failed"), CANCELLED("Cancelled"),
    }

    val active: Boolean get() = state in setOf(State.QUEUED, State.DOWNLOADING, State.MERGING, State.SAVING)
    val progress: Float? get() = if (totalPieces > 0) donePieces.toFloat() / totalPieces else null

    /** Size estimate from the average piece so far. */
    val estimatedBytes: Long? get() = if (donePieces >= 3 && totalPieces > 0) bytes * totalPieces / donePieces else null

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("pageUrl", pageUrl).put("playlistUrl", playlistUrl)
        .put("audioUrl", audioUrl).put("quality", quality).put("state", state.name)
        .put("donePieces", donePieces).put("totalPieces", totalPieces).put("bytes", bytes)
        .put("error", error).put("savedUri", savedUri).put("location", location).put("addedAt", addedAt)

    companion object {
        fun fromJson(o: JSONObject) = StreamDownload(
            id = o.getString("id"),
            title = o.getString("title"),
            pageUrl = o.optString("pageUrl").ifEmpty { null },
            playlistUrl = o.getString("playlistUrl"),
            audioUrl = o.optString("audioUrl").ifEmpty { null },
            quality = o.optString("quality"),
            state = runCatching { State.valueOf(o.getString("state")) }.getOrDefault(State.FAILED),
            donePieces = o.optInt("donePieces"),
            totalPieces = o.optInt("totalPieces"),
            bytes = o.optLong("bytes"),
            error = o.optString("error").ifEmpty { null },
            savedUri = o.optString("savedUri").ifEmpty { null },
            location = o.optString("location").ifEmpty { null },
            addedAt = o.optLong("addedAt"),
        )
    }
}

/**
 * Stream downloads: the list (kept on disk across restarts) and starting, cancelling and
 * retrying them. The work itself runs in [StreamDownloadWorker] under WorkManager, so it
 * carries on with the app closed and resumes after a reboot or a lost connection.
 */
object StreamDownloads {

    private val _items = MutableStateFlow<List<StreamDownload>>(emptyList())
    val items: StateFlow<List<StreamDownload>> = _items.asStateFlow()
    private var loaded = false
    private var lastSave = 0L

    fun load(context: Context) = synchronized(this) {
        if (loaded) return
        loaded = true
        val file = file(context)
        _items.value = try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { StreamDownload.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun start(context: Context, title: String, pageUrl: String?, playlistUrl: String, audioUrl: String?, quality: String): String {
        load(context)
        val d = StreamDownload(UUID.randomUUID().toString(), title, pageUrl, playlistUrl, audioUrl, quality)
        update(context, force = true) { listOf(d) + it }
        enqueue(context, d.id)
        return d.id
    }

    fun retry(context: Context, id: String) {
        update(context, force = true) { list -> list.map { if (it.id == id) it.copy(state = StreamDownload.State.QUEUED, error = null) else it } }
        enqueue(context, id)
    }

    fun cancel(context: Context, id: String) {
        update(context, force = true) { list -> list.map { if (it.id == id) it.copy(state = StreamDownload.State.CANCELLED) else it } }
        WorkManager.getInstance(context).cancelUniqueWork(workName(id))
        workDir(context, id).deleteRecursively()
    }

    /** Drops it from the list; a saved video stays in Downloads. */
    fun remove(context: Context, id: String) {
        val d = get(id)
        if (d?.active == true) cancel(context, id)
        // Kept in app storage (no shared-storage access): the list entry was its only handle.
        if (d?.location?.startsWith("ReelPlay") != true) workDir(context, id).deleteRecursively()
        update(context, force = true) { list -> list.filter { it.id != id } }
    }

    fun get(id: String): StreamDownload? = _items.value.firstOrNull { it.id == id }

    internal fun set(context: Context, id: String, force: Boolean = true, change: (StreamDownload) -> StreamDownload) {
        update(context, force) { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun update(context: Context, force: Boolean, change: (List<StreamDownload>) -> List<StreamDownload>) {
        load(context)
        synchronized(this) {
            _items.value = change(_items.value)
            // Progress ticks often; write the list out on state changes and every few seconds.
            val now = System.currentTimeMillis()
            if (force || now - lastSave > 5_000) {
                lastSave = now
                try {
                    val arr = JSONArray()
                    _items.value.forEach { arr.put(it.toJson()) }
                    val f = file(context)
                    val tmp = File(f.path + ".tmp")
                    tmp.writeText(arr.toString())
                    tmp.renameTo(f)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun enqueue(context: Context, id: String) {
        val request = OneTimeWorkRequestBuilder<StreamDownloadWorker>()
            .setInputData(Data.Builder().putString(StreamDownloadWorker.KEY_ID, id).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .setConstraints(
                androidx.work.Constraints.Builder().setRequiredNetworkType(
                    if (com.eshwar.reelplay.settings.Prefs.wifiOnly) androidx.work.NetworkType.UNMETERED
                    else androidx.work.NetworkType.CONNECTED,
                ).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(id), ExistingWorkPolicy.REPLACE, request)
    }

    fun workDir(context: Context, id: String) =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "streams/$id")

    private fun workName(id: String) = "stream-$id"
    private fun file(context: Context) = File(context.filesDir, "stream_downloads.json")
}
