package com.eshwar.reelplay.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File

/**
 * Starts [intent], turning the ways that can fail into a message instead of a crash: no app to
 * handle it, or (with a URI permission grant attached) Android refusing because this app can't
 * read the file itself any more, e.g. under Android 14+'s "Allow limited access".
 */
fun Context.startActivitySafely(intent: Intent, failure: String = "Couldn't open that") {
    try {
        startActivity(intent)
    } catch (_: SecurityException) {
        Toast.makeText(this, "$failure: ReelPlay no longer has access to this file", Toast.LENGTH_LONG).show()
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(this, "$failure: no app can handle it", Toast.LENGTH_LONG).show()
    }
}

/** Whether this app can still open [uri] for reading. Blocking; call off the main thread. */
fun Context.canRead(uri: Uri): Boolean = when (uri.scheme) {
    "content" -> try {
        contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }
    "file" -> uri.path?.let { File(it).canRead() } == true
    else -> true // http(s) and torrent:// are checked when they play.
}
