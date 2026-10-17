package com.eshwar.reelplay.faces

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import androidx.core.database.sqlite.transaction

/** A photo or video on the phone, as the face index knows it. */
data class MediaRef(
    val key: String,
    val isVideo: Boolean,
    val uri: Uri,
    val name: String,
    /** MediaStore's DATE_MODIFIED: when it changes, the item is looked at again. */
    val modified: Long,
    val durationMs: Long,
    val takenAt: Long,
)

/** One face found in a photo or video frame. */
class FoundFace(val embedding: FloatArray, val quality: Float, val timeMs: Long, val crop: ByteArray?)

class StoredFace(val id: Long, val mediaKey: String, val embedding: FloatArray, val quality: Float)

/**
 * Face data, in the app's private storage only: per photo/video, the faces found in it (the
 * model's embedding plus a small picture of the face for the People screen), and the names the
 * user gave to people. Never leaves the phone; turning the feature off deletes it.
 */
class FaceStore(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, 5) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS media (key TEXT PRIMARY KEY, video INTEGER NOT NULL, uri TEXT NOT NULL, " +
                "name TEXT NOT NULL, modified INTEGER NOT NULL, duration INTEGER NOT NULL, taken INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS face (id INTEGER PRIMARY KEY AUTOINCREMENT, media TEXT NOT NULL, time INTEGER NOT NULL, " +
                "quality REAL NOT NULL, emb BLOB NOT NULL, crop BLOB)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS face_media ON face(media)")
        // A name the user gave someone, kept with what that person looks like, so the name
        // finds them again when the groups are worked out afresh.
        // Faces the user took out of a person ("not this person").
        db.execSQL("CREATE TABLE IF NOT EXISTS hidden (face INTEGER PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS label (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, centre BLOB NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Before version 3, faces were lined up wrongly (left and right mixed up) and failed
        // searches were recorded as "no faces": look at everything again.
        if (oldVersion < 3) db.execSQL("DROP TABLE IF EXISTS label")
        if (oldVersion < 4) {
            // Version 4 skips blurry and tiny faces that earlier versions kept: look again.
            // Names are kept; they're matched to people by face, which hasn't changed.
            db.execSQL("DROP TABLE IF EXISTS face")
            db.execSQL("DROP TABLE IF EXISTS media")
            onCreate(db)
        }
        if (oldVersion < 5) onCreate(db) // adds the hidden table
    }

    /** Photos and videos looked at, and faces found in each. */
    fun stats(): IndexStats {
        var photos = 0; var videos = 0; var photoFaces = 0; var videoFaces = 0
        readableDatabase.rawQuery("SELECT video, COUNT(*) FROM media GROUP BY video", null).use { c ->
            while (c.moveToNext()) if (c.getInt(0) != 0) videos = c.getInt(1) else photos = c.getInt(1)
        }
        readableDatabase.rawQuery(
            "SELECT media.video, COUNT(*) FROM face JOIN media ON face.media = media.key GROUP BY media.video", null,
        ).use { c ->
            while (c.moveToNext()) if (c.getInt(0) != 0) videoFaces = c.getInt(1) else photoFaces = c.getInt(1)
        }
        return IndexStats(photos, videos, photoFaces, videoFaces)
    }

    /** Takes a face out of whichever person it was grouped into. */
    fun hide(faceId: Long) {
        writableDatabase.insertWithOnConflict(
            "hidden", null, ContentValues().apply { put("face", faceId) }, SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    /** Every indexed item's key and the DATE_MODIFIED it was indexed at. */
    fun indexed(): Map<String, Long> {
        val out = HashMap<String, Long>()
        readableDatabase.rawQuery("SELECT key, modified FROM media", null).use { c ->
            while (c.moveToNext()) out[c.getString(0)] = c.getLong(1)
        }
        return out
    }

    fun media(): Map<String, MediaRef> {
        val out = HashMap<String, MediaRef>()
        readableDatabase.rawQuery("SELECT key, video, uri, name, modified, duration, taken FROM media", null).use { c ->
            while (c.moveToNext()) {
                out[c.getString(0)] = MediaRef(
                    c.getString(0), c.getInt(1) != 0, Uri.parse(c.getString(2)), c.getString(3),
                    c.getLong(4), c.getLong(5), c.getLong(6),
                )
            }
        }
        return out
    }

    /** Records what was found in [media], replacing anything from an older version of it. */
    fun put(media: MediaRef, faces: List<FoundFace>) {
        val db = writableDatabase
        db.transaction {
            delete("face", "media = ?", arrayOf(media.key))
            insertWithOnConflict(
                "media", null,
                ContentValues().apply {
                    put("key", media.key); put("video", if (media.isVideo) 1 else 0); put("uri", media.uri.toString())
                    put("name", media.name); put("modified", media.modified); put("duration", media.durationMs)
                    put("taken", media.takenAt)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            for (f in faces) {
                insert(
                    "face", null,
                    ContentValues().apply {
                        put("media", media.key); put("time", f.timeMs); put("quality", f.quality)
                        put("emb", FaceMath.toBytes(f.embedding)); put("crop", f.crop)
                    },
                )
            }
        }
    }

    /** Forgets photos and videos that are no longer on the phone. */
    fun remove(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val db = writableDatabase
        db.transaction {
            for (k in keys) {
                delete("face", "media = ?", arrayOf(k))
                delete("media", "key = ?", arrayOf(k))
            }
        }
    }

    fun faces(): List<StoredFace> {
        val out = ArrayList<StoredFace>()
        readableDatabase.rawQuery("SELECT id, media, emb, quality FROM face WHERE id NOT IN (SELECT face FROM hidden)", null).use { c ->
            while (c.moveToNext()) out += StoredFace(c.getLong(0), c.getString(1), FaceMath.fromBytes(c.getBlob(2)), c.getFloat(3))
        }
        return out
    }

    fun crop(faceId: Long): ByteArray? =
        readableDatabase.rawQuery("SELECT crop FROM face WHERE id = ?", arrayOf(faceId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getBlob(0) else null
        }

    class Label(val id: Long, val name: String, val centre: FloatArray)

    fun labels(): List<Label> {
        val out = ArrayList<Label>()
        readableDatabase.rawQuery("SELECT id, name, centre FROM label", null).use { c ->
            while (c.moveToNext()) out += Label(c.getLong(0), c.getString(1), FaceMath.fromBytes(c.getBlob(2)))
        }
        return out
    }

    /**
     * Replaces the names in [replacing] with [name] for the person who looks like [centre] (raw
     * average face). A blank [name] just removes them.
     */
    fun setLabel(replacing: Collection<Long>, centre: FloatArray, name: String) {
        writableDatabase.transaction {
            for (id in replacing) delete("label", "id = ?", arrayOf(id.toString()))
            if (name.isNotBlank()) {
                insert("label", null, ContentValues().apply { put("name", name.trim()); put("centre", FaceMath.toBytes(centre)) })
            }
        }
    }

    companion object {
        const val NAME = "faces.db"

        /**
         * A saved name belongs to a group whose average face is at least this close to it,
         * both measured from the library's average face.
         */
        const val LABEL_MATCH = 0.5f
    }
}
