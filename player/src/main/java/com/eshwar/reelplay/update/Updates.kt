package com.eshwar.reelplay.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.edit
import com.eshwar.reelplay.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A newer release, as published by update-server's latest.json. */
data class AppUpdate(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val size: Long,
    val notes: String,
) {
    companion object {
        /** Parses the manifest; the APK name is relative to the manifest's own URL. */
        fun parse(json: String, baseUrl: String): AppUpdate {
            val o = JSONObject(json)
            val apk = o.getString("apk")
            require(apk.endsWith(".apk") && '/' !in apk) { "Bad apk name in manifest" }
            return AppUpdate(
                versionCode = o.getInt("versionCode"),
                versionName = o.getString("versionName"),
                apkUrl = URL(URL(baseUrl), apk).toString(),
                sha256 = o.getString("sha256").lowercase(),
                size = o.getLong("size"),
                notes = o.optString("notes"),
            )
        }
    }
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AppUpdate) : UpdateState
    data class Downloading(val update: AppUpdate, val progress: Float) : UpdateState
    /** Android needs "Install unknown apps" allowed for ReelPlay first. */
    data class NeedsPermission(val update: AppUpdate) : UpdateState
    data class Installing(val update: AppUpdate) : UpdateState
    data class Failed(val message: String, val update: AppUpdate?) : UpdateState
}

/**
 * Checks update-server for a newer ReelPlay, downloads it (resuming if interrupted, then
 * verifying size and SHA-256) and hands it to Android's PackageInstaller, which asks the user
 * to confirm and refuses anything not signed with the same key as the installed app.
 */
object Updates {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private const val PREFS = "updates"
    private const val NOTIFIED = "notified_version"
    private const val DISMISSED = "dismissed_version"

    /** Fetches the manifest. Returns the update if it's newer than this build, else null. */
    suspend fun fetch(): AppUpdate? = withContext(Dispatchers.IO) {
        val url = BuildConfig.UPDATE_URL + "latest.json"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        try {
            if (conn.responseCode != 200) throw IOException("Update server returned ${conn.responseCode}")
            val update = AppUpdate.parse(conn.inputStream.bufferedReader().readText(), url)
            update.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
        } finally {
            conn.disconnect()
        }
    }

    /** Checks and publishes the result to [state]. [userAsked] also reports "up to date" and errors. */
    suspend fun check(userAsked: Boolean) {
        val busy = _state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.Installing) return
        if (userAsked) _state.value = UpdateState.Checking
        _state.value = try {
            fetch()?.let { UpdateState.Available(it) } ?: if (userAsked) UpdateState.UpToDate else UpdateState.Idle
        } catch (e: Exception) {
            if (userAsked) UpdateState.Failed("Couldn't check for updates: ${e.message}", null) else UpdateState.Idle
        }
    }

    fun dismiss(context: Context) {
        (state.value as? UpdateState.Available)?.let { prefs(context).edit { putInt(DISMISSED, it.update.versionCode) } }
        _state.value = UpdateState.Idle
    }

    fun wasDismissed(context: Context, update: AppUpdate) = prefs(context).getInt(DISMISSED, 0) >= update.versionCode

    /** True the first time it's asked about [update], so each version is notified once. */
    fun shouldNotify(context: Context, update: AppUpdate): Boolean {
        val p = prefs(context)
        if (p.getInt(NOTIFIED, 0) >= update.versionCode) return false
        p.edit { putInt(NOTIFIED, update.versionCode) }
        return true
    }

    fun showAvailable(update: AppUpdate) {
        _state.value = UpdateState.Available(update)
    }

    /** Downloads, verifies and starts installing [update]. */
    suspend fun install(context: Context, update: AppUpdate) {
        val app = context.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(update)
            return
        }
        try {
            val apk = download(app, update)
            _state.value = UpdateState.Installing(update)
            withContext(Dispatchers.IO) { startInstall(app, apk) }
        } catch (e: Exception) {
            _state.value = UpdateState.Failed(e.message ?: "Update failed", update)
        }
    }

    private suspend fun download(context: Context, update: AppUpdate): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // Older downloads are useless once a newer one is out.
        dir.listFiles()?.filter { !it.name.startsWith("ReelPlay-${update.versionCode}") }?.forEach { it.delete() }
        val file = File(dir, "ReelPlay-${update.versionCode}.apk")
        if (file.length() == update.size && sha256(file) == update.sha256) return@withContext file
        if (file.length() > update.size) file.delete()

        _state.value = UpdateState.Downloading(update, file.length().toFloat() / update.size)
        val conn = URL(update.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        // Pick up where an interrupted download stopped.
        val resumeFrom = file.length()
        if (resumeFrom > 0) conn.setRequestProperty("Range", "bytes=$resumeFrom-")
        try {
            val code = conn.responseCode
            val append = code == HttpURLConnection.HTTP_PARTIAL
            if (code != 200 && !append) throw IOException("Download failed (HTTP $code)")
            RandomAccessFile(file, "rw").use { out ->
                if (append) out.seek(resumeFrom) else out.setLength(0)
                var done = if (append) resumeFrom else 0L
                val buf = ByteArray(64 * 1024)
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        _state.value = UpdateState.Downloading(update, (done.toFloat() / update.size).coerceIn(0f, 1f))
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (file.length() != update.size || sha256(file) != update.sha256) {
            file.delete()
            throw IOException("The download was corrupted; try again")
        }
        file
    }

    private fun startInstall(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, id,
                Intent(context, InstallResultReceiver::class.java),
                // The installer fills in the result, so this one has to be mutable.
                PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0),
            )
            session.commit(callback.intentSender)
        }
    }

    /** Called by [InstallResultReceiver] when the installer reports back. */
    internal fun installFinished(success: Boolean, status: Int, message: String?) {
        val update = (state.value as? UpdateState.Installing)?.update
        if (success) {
            _state.value = UpdateState.Idle
            return
        }
        _state.value = when (status) {
            PackageInstaller.STATUS_FAILURE_ABORTED -> update?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed(
                    "This update is signed with a different key than the ReelPlay you have installed, so " +
                        "Android won't install it over the top. Uninstall ReelPlay once, then install the new " +
                        "version from ${BuildConfig.UPDATE_URL.removeSuffix("reelplay/")} — updates after that " +
                        "install normally.",
                    update,
                )
            PackageInstaller.STATUS_FAILURE_STORAGE -> UpdateState.Failed("Not enough storage to install the update", update)
            else -> UpdateState.Failed("Install failed: ${message ?: "error $status"}", update)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format(java.util.Locale.ROOT, "%02x", it) }
    }
}
