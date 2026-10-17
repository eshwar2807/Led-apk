package com.eshwar.reelplay.settings

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A choice for the player's seek bar and accents. */
data class AccentColor(val name: String, val argb: Long) {
    val color: Color get() = Color(argb)
}

/** User preferences that change how the app looks. Observable, so open screens update live. */
object AppSettings {

    val accents = listOf(
        AccentColor("Netflix red", 0xFFE50914),
        AccentColor("Blue", 0xFF3D8BFF),
        AccentColor("Orange", 0xFFFF7A45),
        AccentColor("Green", 0xFF1DB954),
        AccentColor("Teal", 0xFF14B8A6),
        AccentColor("Purple", 0xFF8B5CF6),
        AccentColor("Pink", 0xFFFF4081),
        AccentColor("Yellow", 0xFFFFC107),
        AccentColor("White", 0xFFFFFFFF),
    )
    val defaultAccent = accents.first()

    private const val PREFS = "settings"
    private const val ACCENT = "player_accent"

    private val _accent = MutableStateFlow(defaultAccent)
    val accent: StateFlow<AccentColor> = _accent.asStateFlow()

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(ACCENT, defaultAccent.argb)
        _accent.value = accents.firstOrNull { it.argb == saved } ?: defaultAccent
    }

    fun setAccent(context: Context, accent: AccentColor) {
        _accent.value = accent
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putLong(ACCENT, accent.argb) }
    }
}
