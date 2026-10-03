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

enum class SortOrder(val label: String) {
    DATE("Date added"),
    NAME("Name"),
    SIZE("Size"),
    DURATION("Length");

    fun sort(videos: List<VideoItem>): List<VideoItem> = when (this) {
        DATE -> videos.sortedByDescending { it.dateAddedSec }
        NAME -> videos.sortedBy { it.name.lowercase() }
        SIZE -> videos.sortedByDescending { it.sizeBytes }
        DURATION -> videos.sortedByDescending { it.durationMs }
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
