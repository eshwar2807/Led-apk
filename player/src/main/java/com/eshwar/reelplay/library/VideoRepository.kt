package com.eshwar.reelplay.library

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val width: Int,
    val height: Int,
    val folderId: String,
    val folderName: String,
)

data class VideoFolder(
    val id: String,
    val name: String,
    val videos: List<VideoItem>,
) {
    val totalBytes: Long get() = videos.sumOf { it.sizeBytes }
}

/** What the library is sorted by. Each has its own words for the two directions, MX-style. */
enum class SortField(val label: String, val ascendingLabel: String, val descendingLabel: String) {
    TITLE("Title", "A to Z", "Z to A"),
    DATE_ADDED("Date added", "Oldest", "Newest"),
    LAST_PLAYED("Last played", "Oldest", "Newest"),
    LENGTH("Length", "Shortest", "Longest"),
    SIZE("Size", "Smallest", "Largest"),
    RESOLUTION("Resolution", "Lowest", "Highest"),
}

/** Which videos to show. */
enum class PlayFilter(val label: String) {
    ALL("All videos"),
    UNPLAYED("Not played yet"),
    IN_PROGRESS("Partly watched"),
    PLAYED("Played"),
    PLAYED_TODAY("Played today"),
    PLAYED_WEEK("Played this week"),
    PLAYED_MONTH("Played this month"),
    ADDED_TODAY("Added today"),
    ADDED_WEEK("Added this week"),
    ADDED_MONTH("Added this month"),
    ;

    /**
     * [playedAt] is when a video was last played (epoch ms, 0 if never), [resumeAt] its saved
     * position (0 if none), [now] the current time in epoch ms.
     */
    fun matches(video: VideoItem, playedAt: Long, resumeAt: Long, now: Long): Boolean {
        val addedAt = video.dateAddedSec * 1000
        return when (this) {
            ALL -> true
            UNPLAYED -> playedAt == 0L
            IN_PROGRESS -> resumeAt > 0L
            PLAYED -> playedAt > 0L
            PLAYED_TODAY -> playedAt > 0L && now - playedAt < DAY
            PLAYED_WEEK -> playedAt > 0L && now - playedAt < 7 * DAY
            PLAYED_MONTH -> playedAt > 0L && now - playedAt < 30 * DAY
            ADDED_TODAY -> now - addedAt < DAY
            ADDED_WEEK -> now - addedAt < 7 * DAY
            ADDED_MONTH -> now - addedAt < 30 * DAY
        }
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
    }
}

val VideoItem.pixels: Long get() = width.toLong() * height

data class LibrarySort(val field: SortField = SortField.DATE_ADDED, val ascending: Boolean = false) {

    val label: String
        get() = "${this.field.label} · ${if (ascending) this.field.ascendingLabel else this.field.descendingLabel}"

    /** Never-played videos go last when sorting by last played, whichever the direction. */
    fun sort(videos: List<VideoItem>, playedAt: (VideoItem) -> Long): List<VideoItem> {
        if (field == SortField.LAST_PLAYED) {
            val (played, never) = videos.partition { playedAt(it) > 0L }
            val sorted = if (ascending) played.sortedBy(playedAt) else played.sortedByDescending(playedAt)
            return sorted + never.sortedBy { it.name.lowercase() }
        }
        val comparator: Comparator<VideoItem> = when (field) {
            SortField.TITLE -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            SortField.DATE_ADDED -> compareBy { it.dateAddedSec }
            SortField.LENGTH -> compareBy { it.durationMs }
            SortField.SIZE -> compareBy { it.sizeBytes }
            SortField.RESOLUTION -> compareBy { it.pixels }
            SortField.LAST_PLAYED -> error("handled above")
        }
        return videos.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    /** Folders follow the same order, judged by their newest/longest/biggest... video. */
    fun sortFolders(folders: List<VideoFolder>, playedAt: (VideoItem) -> Long): List<VideoFolder> {
        val key: (VideoFolder) -> Long = when (field) {
            SortField.TITLE -> return folders.sortedWith(
                compareBy<VideoFolder, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
                    .let { if (ascending) it else it.reversed() },
            )
            SortField.DATE_ADDED -> { f -> f.videos.maxOf { it.dateAddedSec } }
            SortField.LAST_PLAYED -> { f -> f.videos.maxOf(playedAt) }
            SortField.LENGTH -> { f -> f.videos.sumOf { it.durationMs } }
            SortField.SIZE -> { f -> f.totalBytes }
            SortField.RESOLUTION -> { f -> f.videos.maxOf { it.pixels } }
        }
        return if (ascending) folders.sortedBy(key) else folders.sortedByDescending(key)
    }
}

/** Reads every video MediaStore knows about. One query, grouped client-side into folders. */
object VideoRepository {

    private val collection: Uri
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

    suspend fun loadVideos(context: Context): List<VideoItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val result = ArrayList<VideoItem>()
        try {
            context.contentResolver.query(
                collection, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val bucketCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    result += VideoItem(
                        id = id,
                        uri = ContentUris.withAppendedId(collection, id),
                        name = c.getString(nameCol) ?: "Video $id",
                        durationMs = c.getLong(durCol),
                        sizeBytes = c.getLong(sizeCol),
                        dateAddedSec = c.getLong(dateCol),
                        width = c.getInt(wCol),
                        height = c.getInt(hCol),
                        folderId = c.getString(bucketCol) ?: "root",
                        folderName = c.getString(bucketNameCol) ?: "Internal storage",
                    )
                }
            }
        } catch (_: SecurityException) {
            // Permission revoked between the check and the query; show an empty library.
        }
        result
    }

    fun groupIntoFolders(videos: List<VideoItem>): List<VideoFolder> =
        videos.groupBy { it.folderId }
            .map { (id, list) -> VideoFolder(id, list.first().folderName, list) }
            .sortedBy { it.name.lowercase() }
}
