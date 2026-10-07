package com.eshwar.reelplay.report

import android.content.Context
import android.content.Intent
import android.os.Process
import com.eshwar.reelplay.BuildConfig
import com.eshwar.reelplay.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Problem reports sent straight to the developer: posted to the update server, which keeps
 * them for the developer to read. If that fails, the same text can go out through the share
 * sheet (email, chat) instead.
 */
object Reports {

    enum class Kind(val label: String) { PROBLEM("Problem"), CRASH("Crash"), PLAYBACK("Playback error"), TORRENT("Torrent") }

    /**
     * The text that will be sent, shown to the user in full before sending. Everything but the
     * user's own words goes through [Redactor], so no names, addresses or accounts leak in.
     */
    fun compose(context: Context, message: String, details: String, appLog: String?): String {
        val terms = Redactor.personalTerms(context)
        val collected = buildString {
            if (details.isNotBlank()) append(details.trim()).append("\n\n")
            append(CrashReporter.deviceInfo(context))
            if (!appLog.isNullOrBlank()) append("\n\n--- App log ---\n").append(appLog)
        }
        val words = if (message.isNotBlank()) message.trim() + "\n\n" else ""
        return words + Redactor.redact(collected, terms)
    }

    /** This app's own recent log lines; apps may read their own logcat. */
    suspend fun appLog(): String? = withContext(Dispatchers.IO) {
        try {
            val p = ProcessBuilder("logcat", "-d", "-t", "300", "-v", "time", "--pid", Process.myPid().toString())
                .redirectErrorStream(true).start()
            p.inputStream.bufferedReader().use { it.readText() }.takeLast(MAX_LOG).ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /** Posts the report. Throws with a readable message if it couldn't be delivered. */
    suspend fun send(kind: Kind, message: String, body: String) = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("kind", kind.name.lowercase())
            .put("versionName", BuildConfig.VERSION_NAME)
            .put("versionCode", BuildConfig.VERSION_CODE)
            .put("summary", message.trim().lineSequence().firstOrNull().orEmpty().take(120))
            .put("text", body.take(MAX_BODY))
            .toString().toByteArray()
        val conn = URL(BuildConfig.UPDATE_URL + "report").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setFixedLengthStreamingMode(json.size)
        try {
            conn.outputStream.use { it.write(json) }
            val code = conn.responseCode
            if (code == 429) throw IOException("Too many reports from this network just now; try again later")
            if (code !in 200..299) throw IOException("The report server answered HTTP $code")
        } catch (e: IOException) {
            throw if (e.message?.startsWith("The report") == true || e.message?.startsWith("Too many") == true) e
            else IOException("Couldn't reach the report server. Check your connection, or share the report instead.", e)
        } finally {
            conn.disconnect()
        }
    }

    fun shareIntent(kind: Kind, body: String): Intent = Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "All Media Player ${kind.label.lowercase()} report")
            .putExtra(Intent.EXTRA_TEXT, body),
        "Share report",
    )

    private const val MAX_LOG = 60_000
    private const val MAX_BODY = 200_000
}
