package com.eshwar.reelplay.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What to do when a video that was stopped partway is opened again. */
enum class ResumeMode(val label: String, val detail: String) {
    ASK("Ask every time", "Choose between resuming and starting over"),
    RESUME("Resume", "Carry on from where you stopped"),
    START_OVER("Start from the beginning", "Always play from the start"),
}

enum class StartScreen(val label: String) { PLAYER("Media Player"), DOWNLOADER("Downloader") }

enum class Orientation(val label: String) {
    AUTO("Match the video"), LANDSCAPE("Landscape"), PORTRAIT("Portrait"), SYSTEM("Follow phone rotation")
}

/**
 * The app's settings. Reads are plain properties; [version] ticks on every change so open
 * Compose screens can redraw (collect it and read properties after).
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    fun init(context: Context) {
        if (!::sp.isInitialized) sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
    }

    private fun changed() {
        _version.value++
    }

    private fun bool(key: String, def: Boolean) = if (::sp.isInitialized) sp.getBoolean(key, def) else def
    private fun int(key: String, def: Int) = if (::sp.isInitialized) sp.getInt(key, def) else def
    private fun str(key: String) = if (::sp.isInitialized) sp.getString(key, null) else null
    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        sp.edit(action = block)
        changed()
    }
    private inline fun <reified T : Enum<T>> enum(key: String, def: T): T =
        str(key)?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: def

    // ---- Player ----
    var resumeMode: ResumeMode
        get() = enum("resume_mode", ResumeMode.ASK)
        set(v) = put { putString("resume_mode", v.name) }

    var autoPlayNext: Boolean
        get() = bool("auto_play_next", true)
        set(v) = put { putBoolean("auto_play_next", v) }

    var rememberSpeed: Boolean
        get() = bool("remember_speed", true)
        set(v) = put { putBoolean("remember_speed", v) }

    var orientation: Orientation
        get() = enum("orientation", Orientation.AUTO)
        set(v) = put { putString("orientation", v.name) }

    var volumeBoost: Boolean
        get() = bool("volume_boost", true)
        set(v) = put { putBoolean("volume_boost", v) }

    // ---- Gestures ----
    var doubleTapSeekSec: Int
        get() = int("double_tap_seek", 10)
        set(v) = put { putInt("double_tap_seek", v) }

    /** How much a full-width swipe seeks. */
    var swipeSeekSpanSec: Int
        get() = int("swipe_seek_span", 90)
        set(v) = put { putInt("swipe_seek_span", v) }

    var seekGesture: Boolean
        get() = bool("gesture_seek", true)
        set(v) = put { putBoolean("gesture_seek", v) }

    var brightnessGesture: Boolean
        get() = bool("gesture_brightness", true)
        set(v) = put { putBoolean("gesture_brightness", v) }

    var volumeGesture: Boolean
        get() = bool("gesture_volume", true)
        set(v) = put { putBoolean("gesture_volume", v) }

    // ---- Library ----
    var startScreen: StartScreen
        get() = enum("start_screen", StartScreen.PLAYER)
        set(v) = put { putString("start_screen", v.name) }

    var showNewTags: Boolean
        get() = bool("show_new", true)
        set(v) = put { putBoolean("show_new", v) }

    var showThumbnails: Boolean
        get() = bool("show_thumbnails", true)
        set(v) = put { putBoolean("show_thumbnails", v) }

    // ---- Downloads ----
    var wifiOnly: Boolean
        get() = bool("wifi_only", false)
        set(v) = put { putBoolean("wifi_only", v) }

    /** KB/s; 0 is unlimited. */
    var torrentDownloadLimitKb: Int
        get() = int("torrent_down_kb", 0)
        set(v) = put { putInt("torrent_down_kb", v) }

    var torrentUploadLimitKb: Int
        get() = int("torrent_up_kb", 0)
        set(v) = put { putInt("torrent_up_kb", v) }

    // ---- General ----
    var autoUpdateCheck: Boolean
        get() = bool("auto_update_check", true)
        set(v) = put { putBoolean("auto_update_check", v) }
}
