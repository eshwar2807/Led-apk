package com.eshwar.reelplay.torrent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentStatus
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

enum class DownloadState(val label: String) {
    STARTING("Starting"),
    CHECKING("Checking files"),
    DOWNLOADING("Downloading"),
    PAUSED("Paused"),
    SAVING("Saving to Downloads"),
    DONE("Done"),
    FAILED("Failed"),
}

/** A finished file, as it ended up in shared storage. */
data class SavedFile(val name: String, val uri: String, val mime: String) {
    val isVideo: Boolean get() = mime.startsWith("video/")
}

/** What survives an app restart. The .torrent itself lives next to the registry. */
data class DownloadRecord(
    val id: String,
    val name: String,
    val selected: List<Int>,
    val totalBytes: Long,
    val addedAt: Long,
    val paused: Boolean = false,
    val done: Boolean = false,
    val saved: List<SavedFile> = emptyList(),
    val error: String? = null,
    /** Where the files were saved, for display. */
    val location: String? = null,
    /** Trackers from the magnet link, which the saved .torrent doesn't contain. */
    val trackers: List<String> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("selected", JSONArray(selected))
        .put("totalBytes", totalBytes).put("addedAt", addedAt)
        .put("paused", paused).put("done", done)
        .put("error", error).put("location", location)
        .put("trackers", JSONArray(trackers))
        .put(
            "saved",
            JSONArray(saved.map { JSONObject().put("name", it.name).put("uri", it.uri).put("mime", it.mime) }),
        )

    companion object {
        fun fromJson(o: JSONObject) = DownloadRecord(
            id = o.getString("id"),
            name = o.getString("name"),
            selected = o.getJSONArray("selected").let { a -> List(a.length()) { a.getInt(it) } },
            totalBytes = o.getLong("totalBytes"),
            addedAt = o.getLong("addedAt"),
            paused = o.optBoolean("paused"),
            done = o.optBoolean("done"),
            error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
            location = o.optString("location").takeIf { it.isNotEmpty() && it != "null" },
            trackers = o.optJSONArray("trackers")?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty(),
            saved = o.optJSONArray("saved")?.let { a ->
                List(a.length()) {
                    val s = a.getJSONObject(it)
                    SavedFile(s.getString("name"), s.getString("uri"), s.getString("mime"))
                }
            }.orEmpty(),
        )
    }
}

/** A download as the UI sees it: the record plus live numbers. */
data class DownloadItem(
    val record: DownloadRecord,
    val state: DownloadState,
    val files: List<TorrentFile>,
    val doneBytes: Long,
    val downloadRate: Int,
    val uploadRate: Int,
    val peers: Int,
    val seeds: Int,
) {
    val id get() = record.id
    val progress: Float get() = if (record.totalBytes > 0) (doneBytes.toFloat() / record.totalBytes).coerceIn(0f, 1f) else 0f
    val etaSeconds: Long? get() = if (downloadRate > 0) (record.totalBytes - doneBytes).coerceAtLeast(0) / downloadRate else null
    val isActive: Boolean get() = state in setOf(DownloadState.STARTING, DownloadState.CHECKING, DownloadState.DOWNLOADING, DownloadState.SAVING)
}

/**
 * Whole-torrent downloads from .torrent files and magnet links. Data downloads into app storage
 * (libtorrent needs real file paths); when every chosen file is complete it is copied to
 * Download/ReelPlay where galleries, file managers and the library can see it, and the working
 * copy is dropped. The list survives restarts: unfinished torrents are re-added on launch and
 * libtorrent re-verifies what is already on disk before carrying on.
 */
object TorrentDownloads {

    private lateinit var appContext: Context
    private val lock = Any()
    private val records = LinkedHashMap<String, DownloadRecord>()
    // Touched from the ticker, the UI and the restore thread at once.
    private val metas = ConcurrentHashMap<String, TorrentMeta>()
    private val saving: MutableSet<String> = ConcurrentHashMap.newKeySet()
    // One bad status read must never take the whole app down mid-download.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
            Log.w("TorrentDownloads", "Background download task failed", e)
        },
    )
    private var ticker: Job? = null
    @Volatile private var restored = false

    private val _items = MutableStateFlow<List<DownloadItem>>(emptyList())
    val items: StateFlow<List<DownloadItem>> = _items.asStateFlow()

    private fun dir(): File = File(appContext.filesDir, "downloads").apply { mkdirs() }
    private fun dataDir(id: String): File = File(File(TorrentEngine.storageRoot(appContext), "downloads"), id)
    private fun torrentFile(id: String) = File(dir(), "$id.torrent")
    private fun registry() = File(dir(), "registry.json")

    /** Loads the saved list and restarts unfinished downloads. Blocking; safe to call repeatedly. */
    fun restore(context: Context, startPaused: Boolean = false) {
        synchronized(lock) {
            if (restored) return
            appContext = context.applicationContext
            restored = true
            readRegistry().forEach { records[it.id] = if (startPaused && !it.done) it.copy(paused = true) else it }
        }
        if (startPaused) persist()
        records.values.filter { !it.done }.forEach { record ->
            try {
                val bytes = torrentFile(record.id).readBytes()
                val meta = TorrentMeta.parse(bytes, record.trackers)
                metas[record.id] = meta
                addToSession(record, meta)
            } catch (e: Exception) {
                update(record.id) { it.copy(error = "Couldn't resume: ${e.message}") }
            }
        }
        ensureTicker()
        publish()
    }

    fun contains(infoHash: String): Boolean = synchronized(lock) { records[infoHash]?.done == false }

    fun hasActive(): Boolean = items.value.any { it.isActive }

    /** Starts downloading [selected] files of [meta]. Blocking; call off the main thread. */
    fun add(context: Context, meta: TorrentMeta, selected: Set<Int>) {
        restore(context)
        require(selected.isNotEmpty()) { "Choose at least one file" }
        synchronized(lock) {
            records[meta.infoHash]?.let { existing ->
                if (!existing.done) throw IOException("This torrent is already in Downloads")
            }
        }
        // Everything chosen has to fit, with room to spare, or libtorrent stalls with a full disk.
        TorrentEngine.requireSpace(context, selected.sumOf { meta.files[it].size })
        torrentFile(meta.infoHash).writeBytes(meta.bytes)
        val record = DownloadRecord(
            id = meta.infoHash,
            name = meta.name,
            selected = selected.sorted(),
            totalBytes = selected.sumOf { meta.files[it].size },
            addedAt = System.currentTimeMillis(),
            trackers = meta.trackers,
        )
        synchronized(lock) {
            records.remove(record.id) // A finished copy of the same torrent is replaced.
            records[record.id] = record
            metas[record.id] = meta
        }
        persist()
        addToSession(record, meta)
        ensureTicker()
        publish()
    }

    private fun addToSession(record: DownloadRecord, meta: TorrentMeta) {
        val s = TorrentEngine.session(appContext)
        val priorities = Array(meta.files.size) { if (it in record.selected) Priority.DEFAULT else Priority.IGNORE }
        val dir = dataDir(record.id).apply { mkdirs() }
        val handle = TorrentEngine.addTorrent(s, meta, dir, priorities)
        // Pause and resume are the user's call, not libtorrent's queue manager's.
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
        if (record.paused) handle.pause() else handle.resume()
    }

    /** Swarm diagnostics for an unfinished download, or null. Blocking; call off the main thread. */
    fun health(id: String): SwarmHealth? = handle(id)?.let { SwarmHealth.of(it) }

    private fun handle(id: String): TorrentHandle? = try {
        metas[id]?.let { TorrentEngine.session(appContext).find(it.info.infoHash()) }?.takeIf { it.isValid }
    } catch (_: Exception) {
        null
    }

    fun pause(id: String) {
        handle(id)?.pause()
        update(id) { it.copy(paused = true) }
        publish()
    }

    fun resume(id: String) {
        handle(id)?.resume()
        update(id) { it.copy(paused = false, error = null) }
        publish()
        ensureTicker()
    }

    /** Drops a download. Unfinished data always goes; saved files only if [deleteSaved]. */
    fun remove(id: String, deleteSaved: Boolean) {
        val record = synchronized(lock) { records.remove(id) } ?: return
        handle(id)?.let {
            try {
                TorrentEngine.session(appContext).remove(it, SessionHandle.DELETE_FILES)
            } catch (_: Exception) {
            }
        }
        metas.remove(id)
        dataDir(id).deleteRecursively()
        torrentFile(id).delete()
        if (deleteSaved) {
            record.saved.forEach { f ->
                try {
                    appContext.contentResolver.delete(f.uri.toUri(), null, null)
                } catch (_: Exception) {
                    // Saved by an earlier install, so no longer ours to delete; leave it.
                }
            }
        }
        persist()
        publish()
    }

    /** A stream of one file of an unfinished download, for "play while downloading". */
    fun streamFile(context: Context, id: String, fileIndex: Int): TorrentStream {
        restore(context)
        val meta = metas[id] ?: throw IOException("This download isn't running")
        val handle = handle(id) ?: throw IOException("This download isn't running")
        if (handle.filePriority(fileIndex) == Priority.IGNORE) throw IOException("That file isn't part of this download")
        val file = meta.files[fileIndex]
        handle.resume()
        update(id) { it.copy(paused = false) }
        ensureTicker()
        return TorrentEngine.attach(TorrentStream(handle, meta, file, File(dataDir(id), file.path), ownsTorrent = false))
    }

    // ---- Progress and completion ----

    private fun ensureTicker() {
        synchronized(lock) {
            if (ticker?.isActive == true) return
            ticker = scope.launch {
                while (isActive) {
                    try {
                        publish()
                    } catch (e: Exception) {
                        Log.w("TorrentDownloads", "Progress update failed", e)
                    }
                    // Nothing moving: stop ticking until a download is added or resumed.
                    if (_items.value.none { it.isActive }) break
                    delay(1000)
                }
            }
        }
    }

    private fun publish() {
        val snapshot = synchronized(lock) { records.values.toList() }
        _items.value = snapshot.sortedByDescending { it.addedAt }.map { record -> itemFor(record) }
    }

    private fun itemFor(record: DownloadRecord): DownloadItem {
        val meta = metas[record.id]
        val files = meta?.let { m -> record.selected.mapNotNull { m.files.getOrNull(it) } }.orEmpty()
        if (record.done) return DownloadItem(record, DownloadState.DONE, files, record.totalBytes, 0, 0, 0, 0)
        if (record.id in saving) return DownloadItem(record, DownloadState.SAVING, files, record.totalBytes, 0, 0, 0, 0)
        val handle = handle(record.id)
        if (handle == null || record.error != null) {
            val state = if (record.error != null) DownloadState.FAILED else DownloadState.STARTING
            return DownloadItem(record, state, files, 0, 0, 0, 0, 0)
        }
        val s = try {
            handle.status()
        } catch (_: Exception) {
            return DownloadItem(record, DownloadState.STARTING, files, 0, 0, 0, 0, 0)
        }
        if (s.errorCode().isError) {
            update(record.id) { it.copy(error = s.errorCode().message) }
        }
        val state = when {
            record.paused -> DownloadState.PAUSED
            s.state() == TorrentStatus.State.CHECKING_FILES || s.state() == TorrentStatus.State.CHECKING_RESUME_DATA ->
                DownloadState.CHECKING
            else -> DownloadState.DOWNLOADING
        }
        if (s.isFinished && s.totalWanted() > 0 && s.totalWantedDone() >= s.totalWanted() && meta != null) {
            startSaving(record, meta)
        }
        return DownloadItem(
            record, state, files,
            doneBytes = s.totalWantedDone(),
            downloadRate = s.downloadPayloadRate(),
            uploadRate = s.uploadPayloadRate(),
            peers = s.numPeers(),
            seeds = s.numSeeds(),
        )
    }

    private fun startSaving(record: DownloadRecord, meta: TorrentMeta) {
        // Someone is watching it from the working copy; finish up once they stop.
        if (TorrentEngine.isStreaming(record.id)) return
        synchronized(lock) {
            if (!saving.add(record.id)) return
        }
        scope.launch {
            try {
                val result = DownloadSaver.save(appContext, meta, record.selected, dataDir(record.id))
                // Copied out: stop seeding, and drop the working copy unless files still live there.
                handle(record.id)?.let {
                    try {
                        val session = TorrentEngine.session(appContext)
                        if (result.keptInApp) session.remove(it) else session.remove(it, SessionHandle.DELETE_FILES)
                    } catch (_: Exception) {
                    }
                }
                if (!result.keptInApp) dataDir(record.id).deleteRecursively()
                torrentFile(record.id).delete()
                update(record.id) { it.copy(done = true, saved = result.files, location = result.location, error = null) }
                onFinished?.invoke(record.name)
            } catch (e: Throwable) {
                update(record.id) { it.copy(error = "Couldn't save: ${e.message}") }
            } finally {
                synchronized(lock) { saving.remove(record.id) }
                publish()
            }
        }
    }

    /** Set by the service to post a "download complete" notification. */
    @Volatile var onFinished: ((String) -> Unit)? = null

    // ---- Persistence ----

    private fun update(id: String, change: (DownloadRecord) -> DownloadRecord) {
        synchronized(lock) {
            val current = records[id] ?: return
            records[id] = change(current)
        }
        persist()
    }

    private fun persist() {
        val json = synchronized(lock) { JSONArray(records.values.map { it.toJson() }).toString() }
        try {
            val tmp = File(dir(), "registry.json.tmp")
            tmp.writeText(json)
            tmp.renameTo(registry())
        } catch (_: Exception) {
        }
    }

    private fun readRegistry(): List<DownloadRecord> = try {
        val a = JSONArray(registry().readText())
        List(a.length()) { DownloadRecord.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) {
        emptyList()
    }
}

/** Copies finished files into Download/ReelPlay/<torrent name>/, keeping the torrent's folders. */
private object DownloadSaver {

    class Result(val files: List<SavedFile>, val location: String, val keptInApp: Boolean)

    fun save(context: Context, meta: TorrentMeta, selected: List<Int>, from: File): Result {
        val folder = "ReelPlay/" + clean(meta.name)
        // Copying out needs the same space again. If it isn't there (a 14 GB film on a full
        // phone), keep the files where they are rather than fail at the last step.
        val total = selected.sumOf { meta.files[it].size }
        var keptInApp = TorrentEngine.freeBytes(context) < total + 200L * 1024 * 1024
        val saved = selected.map { index ->
            val file = meta.files[index]
            val source = File(from, file.path)
            // Paths inside the torrent start with its own name; keep only the folders below that.
            val inner = file.path.split('/').drop(1).dropLast(1).joinToString("/") { clean(it) }
            val sub = if (inner.isEmpty()) folder else "$folder/$inner"
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(file.name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            val uri = try {
                if (keptInApp) throw IOException("Not enough space to copy")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) toMediaStore(context, source, file.name, mime, sub)
                else toPublicFolder(context, source, file.name, sub)
            } catch (_: Exception) {
                // No shared-storage access (old Android without the permission, or full):
                // keep it in app storage and share it from there.
                keptInApp = true
                FileProvider.getUriForFile(context, context.packageName + ".files", source)
            }
            SavedFile(file.name, uri.toString(), mime)
        }
        val location = if (keptInApp) "ReelPlay's own storage" else "${Environment.DIRECTORY_DOWNLOADS}/$folder"
        return Result(saved, location, keptInApp)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun toMediaStore(context: Context, source: File, name: String, mime: String, sub: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$sub")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
            ?: throw IOException("Couldn't create $name in Downloads")
        try {
            resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Couldn't write $name")
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    @Suppress("DEPRECATION")
    private fun toPublicFolder(context: Context, source: File, name: String, sub: String): Uri {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create $dir")
        val dest = File(dir, name)
        source.copyTo(dest, overwrite = true)
        android.media.MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null)
        // A content URI, so sharing it to other apps works.
        return FileProvider.getUriForFile(context, context.packageName + ".files", dest)
    }

    /** Keeps names legal on FAT/exFAT SD cards and in MediaStore paths. */
    private fun clean(name: String): String =
        name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').ifEmpty { "download" }.take(120)
}
