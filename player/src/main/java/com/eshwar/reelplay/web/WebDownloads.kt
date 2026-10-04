package com.eshwar.reelplay.web

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.content.edit

/** A file download from "Find videos", as Android's download manager reports it. */
data class WebDownload(
    val id: Long,
    val title: String,
    val status: Status,
    val doneBytes: Long,
    /** -1 until the server says how big the file is. */
    val totalBytes: Long,
    val localUri: String?,
    val problem: String?,
) {
    enum class Status(val label: String) {
        WAITING("Waiting"), DOWNLOADING("Downloading"), PAUSED("Paused"), DONE("Done"), FAILED("Failed")
    }

    val progress: Float? get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null
    val active: Boolean get() = status == Status.WAITING || status == Status.DOWNLOADING || status == Status.PAUSED
}

/**
 * Downloads started from "Find videos". Android's download manager does the work (it keeps
 * going in the background and retries); this keeps track of which downloads are ours so the
 * Downloads screen can show their progress and speed.
 */
object WebDownloads {

    private const val PREFS = "web_downloads"
    private const val IDS = "ids"

    fun start(context: Context, url: String, name: String): Long {
        val safe = name.replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").ifBlank { "video.mp4" }
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle(safe)
            .setDescription("ReelPlay")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "ReelPlay/$safe")
        val id = manager(context).enqueue(request)
        prefs(context).edit { putStringSet(IDS, ids(context).map(Long::toString).toSet() + id.toString()) }
        return id
    }

    /** Current state of each of our downloads, newest first. Cheap; poll it while on screen. */
    fun list(context: Context): List<WebDownload> {
        val ids = ids(context)
        if (ids.isEmpty()) return emptyList()
        val found = ArrayList<WebDownload>()
        try {
            manager(context).query(DownloadManager.Query().setFilterById(*ids.toLongArray()))?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
                val titleCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)
                val statusCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                val reasonCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)
                val doneCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val uriCol = c.getColumnIndexOrThrow(DownloadManager.COLUMN_LOCAL_URI)
                while (c.moveToNext()) {
                    val status = c.getInt(statusCol)
                    val reason = c.getInt(reasonCol)
                    found += WebDownload(
                        id = c.getLong(idCol),
                        title = c.getString(titleCol) ?: "Video",
                        status = when (status) {
                            DownloadManager.STATUS_RUNNING -> WebDownload.Status.DOWNLOADING
                            DownloadManager.STATUS_PAUSED -> WebDownload.Status.PAUSED
                            DownloadManager.STATUS_SUCCESSFUL -> WebDownload.Status.DONE
                            DownloadManager.STATUS_FAILED -> WebDownload.Status.FAILED
                            else -> WebDownload.Status.WAITING
                        },
                        doneBytes = c.getLong(doneCol),
                        totalBytes = c.getLong(totalCol),
                        localUri = c.getString(uriCol),
                        problem = when (status) {
                            DownloadManager.STATUS_PAUSED -> pausedReason(reason)
                            DownloadManager.STATUS_FAILED -> failedReason(reason)
                            else -> null
                        },
                    )
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        // Forget downloads the user cleared from the system's own list.
        val live = found.map { it.id }.toSet()
        if (live.size != ids.size) prefs(context).edit { putStringSet(IDS, live.map(Long::toString).toSet()) }
        return found.sortedByDescending { it.id }
    }

    /** Cancels an unfinished download, or removes a finished one from the list (and its file if [deleteFile]). */
    fun remove(context: Context, id: Long, deleteFile: Boolean) {
        val item = list(context).firstOrNull { it.id == id }
        if (item == null || item.status != WebDownload.Status.DONE || deleteFile) {
            manager(context).remove(id) // Also deletes the (partial) file.
        }
        prefs(context).edit { putStringSet(IDS, ids(context).filter { it != id }.map(Long::toString).toSet()) }
    }

    /** A content:// URI for a finished download that the player can open. */
    fun fileUri(context: Context, id: Long): Uri? = try {
        manager(context).getUriForDownloadedFile(id)
    } catch (_: Exception) {
        null
    }

    private fun pausedReason(reason: Int) = when (reason) {
        DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for a network connection"
        DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi (too big for mobile data)"
        DownloadManager.PAUSED_WAITING_TO_RETRY -> "Connection dropped; retrying shortly"
        else -> "Paused"
    }

    private fun failedReason(reason: Int) = when (reason) {
        DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage"
        DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "A file with this name already exists in Download/ReelPlay"
        DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "The site redirected too many times"
        DownloadManager.ERROR_HTTP_DATA_ERROR, DownloadManager.ERROR_CANNOT_RESUME -> "The connection broke and the site can't resume it"
        in 400..599 -> "The site refused the download (HTTP $reason)"
        else -> "Download failed ($reason)"
    }

    private fun ids(context: Context): Set<Long> =
        prefs(context).getStringSet(IDS, emptySet()).orEmpty().mapNotNull(String::toLongOrNull).toSet()

    private fun manager(context: Context) = context.getSystemService(DownloadManager::class.java)
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/**
 * Download speed from successive byte counts, smoothed so the number doesn't jump about.
 * Pure, so it's unit tested.
 */
class RateMeter(private val smoothing: Float = 0.3f) {
    private var lastBytes = -1L
    private var lastMs = 0L

    /** Bytes per second, or null before there's anything to compare with. */
    var rate: Long? = null
        private set

    fun sample(bytes: Long, nowMs: Long): Long? {
        if (lastBytes >= 0 && nowMs > lastMs) {
            val instant = ((bytes - lastBytes).coerceAtLeast(0) * 1000.0 / (nowMs - lastMs)).toLong()
            rate = rate?.let { (it + smoothing * (instant - it)).toLong() } ?: instant
        }
        lastBytes = bytes
        lastMs = nowMs
        return rate
    }
}
