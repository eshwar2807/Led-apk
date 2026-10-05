package com.eshwar.reelplay.player

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.net.toUri

/** Remembers where each video was left off and when it was last played, MX-style. */
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
                putLong(playedKey(uri), System.currentTimeMillis())
                putString(LAST_URI, uri.toString())
                putString(LAST_TITLE, title)
            }
        }
    }

    /** When each video was last played (epoch ms), keyed by URI string; one read for a whole list. */
    fun playedTimes(): Map<String, Long> = prefs.all.mapNotNull { (k, v) ->
        if (k.startsWith(PLAYED) && v is Long) k.removePrefix(PLAYED) to v else null
    }.toMap()

    /** Saved resume positions, keyed by URI string. */
    fun resumePositions(): Map<String, Long> = prefs.all.mapNotNull { (k, v) ->
        if (k.startsWith(POS) && v is Long) k.removePrefix(POS) to v else null
    }.toMap()

    val lastPlayed: Pair<Uri, String>?
        get() {
            val uri = prefs.getString(LAST_URI, null) ?: return null
            return uri.toUri() to (prefs.getString(LAST_TITLE, null) ?: "Video")
        }

    fun clearLastPlayed() = prefs.edit {
        remove(LAST_URI)
        remove(LAST_TITLE)
    }

    /** Drops everything remembered about [uri] (it was deleted). */
    fun forget(uri: Uri) = prefs.edit {
        remove(key(uri))
        remove(playedKey(uri))
        if (prefs.getString(LAST_URI, null) == uri.toString()) {
            remove(LAST_URI)
            remove(LAST_TITLE)
        }
    }

    var playbackSpeed: Float
        get() = prefs.getFloat(SPEED, 1f)
        set(value) = prefs.edit { putFloat(SPEED, value) }

    private fun key(uri: Uri) = "$POS$uri"
    private fun playedKey(uri: Uri) = "$PLAYED$uri"

    private companion object {
        const val LAST_URI = "last_uri"
        const val LAST_TITLE = "last_title"
        const val SPEED = "speed"
        const val POS = "pos:"
        const val PLAYED = "played:"
    }
}
