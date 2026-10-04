package com.eshwar.reelplay

import android.app.ActivityManager
import android.app.Application
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.DateFormat
import java.util.Date

/**
 * Remembers why the app last died, so it can be shown and shared on the next launch. Java
 * crashes are written out as they happen; native crashes, freezes (ANRs) and low-memory kills
 * come from Android's own exit records (Android 11+), since nothing in-process survives them.
 */
object CrashReporter {

    private const val FILE = "last_crash.txt"
    private const val PREFS = "crash_reporter"
    private const val SEEN = "seen_exit_ms"

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                File(app.filesDir, FILE).writeText(
                    "Crash in thread \"${thread.name}\" at ${DateFormat.getDateTimeInstance().format(Date())}\n\n$trace",
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A report about the last abnormal exit not yet shown, or null. */
    fun pending(context: Context): String? {
        val sections = mutableListOf<String>()
        val javaCrash = File(context.filesDir, FILE)
        if (javaCrash.exists()) sections += javaCrash.readText()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val seen = prefs.getLong(SEEN, 0L)
            val am = context.getSystemService(ActivityManager::class.java)
            val exits = try {
                am.getHistoricalProcessExitReasons(context.packageName, 0, 5)
            } catch (_: Exception) {
                emptyList()
            }
            // Anything from the last week not shown yet; this survives updating the app, so a
            // crash in an older version is still reported by the fixed one.
            val since = maxOf(seen, System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000)
            val latest = exits.firstOrNull { it.timestamp > since && it.pid != Process.myPid() }
            if (exits.isNotEmpty()) prefs.edit { putLong(SEEN, exits.maxOf { it.timestamp }) }
            if (latest != null) describe(latest, haveJavaTrace = javaCrash.exists())?.let { sections += it }
        }
        if (sections.isEmpty()) return null
        return sections.joinToString("\n\n") + "\n\n" + deviceInfo(context)
    }

    /** Whether the previous run ended in a crash, freeze or memory kill. Doesn't consume the report. */
    fun lastRunCrashed(context: Context): Boolean {
        if (File(context.filesDir, FILE).exists()) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        val last = try {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 2)
                .firstOrNull { it.pid != Process.myPid() }
        } catch (_: Exception) {
            null
        } ?: return false
        return last.reason in setOf(
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_LOW_MEMORY,
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
        )
    }

    fun dismiss(context: Context) {
        File(context.filesDir, FILE).delete()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun describe(exit: ApplicationExitInfo, haveJavaTrace: Boolean): String? {
        val what = when (exit.reason) {
            ApplicationExitInfo.REASON_CRASH ->
                if (haveJavaTrace) null else "App crash (from a version that didn't record details)"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash (in the video or torrent engine)"
            ApplicationExitInfo.REASON_ANR -> "The app froze and Android closed it (ANR)"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Android closed ReelPlay to free memory"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Android closed ReelPlay for using too many resources"
            ApplicationExitInfo.REASON_SIGNALED -> "ReelPlay was killed by a signal (${exit.status})"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "ReelPlay failed to start"
            else -> null // Normal exits, user swipes, updates.
        } ?: return null
        val detail = buildString {
            append(what)
            append("\nWhen: ").append(DateFormat.getDateTimeInstance().format(Date(exit.timestamp)))
            exit.description?.let { append("\nDetails: ").append(it) }
            append("\nMemory in use: ").append(exit.pss / 1024).append(" MB")
            // ANR traces are plain text and say exactly where it was stuck.
            if (exit.reason == ApplicationExitInfo.REASON_ANR) {
                try {
                    exit.traceInputStream?.bufferedReader()?.use { r ->
                        append("\n\n").append(r.readText().take(12_000))
                    }
                } catch (_: Exception) {
                }
            }
        }
        return detail
    }

    fun deviceInfo(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        val free = try {
            android.os.StatFs((context.getExternalFilesDir(null) ?: context.filesDir).path).availableBytes / (1024 * 1024)
        } catch (_: Exception) {
            -1
        }
        return "ReelPlay $version · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()} · $free MB free"
    }
}
