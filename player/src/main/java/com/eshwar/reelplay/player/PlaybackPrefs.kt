package com.eshwar.reelplay.player

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

/** Remembers where each video was left off, plus the last thing played, MX-style. */
class PlaybackPrefs(context: Context) {

    private val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun position(uri: Uri): Long = prefs.getLong(key(uri), 0L)

    /** Fraction watched, for the progress strip under library thumbnails. */
    fun progress(uri: Uri, durationMs: Long): Float =
        if (durationMs <= 0) 0f else (position(uri).toFloat() / durationMs).coerceIn(0f, 1f)

    fun save(uri: Uri, positionMs: Long, durationMs: Long, title: String) {
        prefs.edit {
            // Finished, or barely started: next time starts from the top.
            if (positionMs < 3_000 || (durationMs > 0 && positionMs > durationMs - 5_000)) {
                remove(key(uri))
            } else {
                putLong(key(uri), positionMs)
            }
            // A torrent only exists while it's streaming, so it can't be "continued" later.
            if (uri.scheme != "torrent") {
                putString(LAST_URI, uri.toString())
                putString(LAST_TITLE, title)
            }
        }
    }

    val lastPlayed: Pair<Uri, String>?
        get() {
            val uri = prefs.getString(LAST_URI, null) ?: return null
            return uri.toUri() to (prefs.getString(LAST_TITLE, null) ?: "Video")
        }

    var playbackSpeed: Float
        get() = prefs.getFloat(SPEED, 1f)
        set(value) = prefs.edit { putFloat(SPEED, value) }

    private fun key(uri: Uri) = "pos:$uri"

    private companion object {
        const val LAST_URI = "last_uri"
        const val LAST_TITLE = "last_title"
        const val SPEED = "speed"
    }
}
