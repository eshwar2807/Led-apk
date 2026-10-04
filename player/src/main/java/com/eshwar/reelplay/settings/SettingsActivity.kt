package com.eshwar.reelplay.settings

import com.eshwar.reelplay.report.ReportDialog
import com.eshwar.reelplay.report.Reports
import androidx.compose.runtime.mutableStateOf
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eshwar.reelplay.BuildConfig
import com.eshwar.reelplay.player.SeekBar
import com.eshwar.reelplay.ui.ReelPlayTheme
import com.eshwar.reelplay.update.UpdateDialog
import com.eshwar.reelplay.update.UpdateWorker
import com.eshwar.reelplay.torrent.TorrentEngine
import com.eshwar.reelplay.update.Updates
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ReelPlayTheme {
                SettingsScreen(onClose = ::finish)
                UpdateDialog()
            }
        }
    }

    companion object {
        fun intent(context: Context) = Intent(context, SettingsActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent by AppSettings.accent.collectAsState()
    var preview by remember { mutableFloatStateOf(0.4f) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Settings") },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Section("Player")
            Text("Seek bar colour", fontWeight = FontWeight.Medium)
            Text(
                accent.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            // Live preview on a dark strip, the way it looks over video.
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SeekBar(
                    value = preview,
                    buffered = (preview + 0.25f).coerceAtMost(1f),
                    color = accent.color,
                    onSeeking = { preview = it },
                    onSeek = { preview = it },
                )
            }
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AppSettings.accents.forEach { option ->
                    val selected = option == accent
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(option.color)
                            .border(
                                if (selected) 3.dp else 1.dp,
                                if (selected) MaterialTheme.colorScheme.onSurface else Color(0x33FFFFFF),
                                CircleShape,
                            )
                            .clickable { AppSettings.setAccent(context, option) }
                            .semantics {
                                contentDescription = option.name
                                this.selected = selected
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Icon(
                                Icons.Rounded.Check, null,
                                tint = if (option.color.luminance() > 0.5f) Color.Black else Color.White,
                            )
                        }
                    }
                }
            }

            // Redraw when a setting changes.
            Prefs.version.collectAsState().value

            Spacer(Modifier.height(20.dp))
            ChoiceRow(
                "Resume playback",
                Prefs.resumeMode, ResumeMode.entries, { it.label }, { it.detail },
            ) { Prefs.resumeMode = it }
            SwitchRow(
                "Play next video automatically",
                "When one video ends, the next in the folder starts", Prefs.autoPlayNext,
            ) { Prefs.autoPlayNext = it }
            SwitchRow(
                "Remember playback speed",
                "Off: every video starts at 1x", Prefs.rememberSpeed,
            ) { Prefs.rememberSpeed = it }
            ChoiceRow("Screen orientation", Prefs.orientation, Orientation.entries, { it.label }) { Prefs.orientation = it }
            SwitchRow(
                "Volume boost",
                "Let the volume gesture go past 100% for quiet videos", Prefs.volumeBoost,
            ) { Prefs.volumeBoost = it }

            Spacer(Modifier.height(28.dp))
            Section("Gestures")
            ChoiceRow(
                "Double-tap to skip", Prefs.doubleTapSeekSec, listOf(5, 10, 15, 30, 60),
                { "$it seconds" },
            ) { Prefs.doubleTapSeekSec = it }
            ChoiceRow(
                "Swipe to seek: one screen width is", Prefs.swipeSeekSpanSec, listOf(30, 60, 90, 180, 300),
                { if (it < 60) "$it seconds" else "${it / 60} min${if (it % 60 != 0) " ${it % 60} s" else ""}" },
            ) { Prefs.swipeSeekSpanSec = it }
            SwitchRow("Swipe left/right to seek", null, Prefs.seekGesture) { Prefs.seekGesture = it }
            SwitchRow("Swipe on the left side for brightness", null, Prefs.brightnessGesture) { Prefs.brightnessGesture = it }
            SwitchRow("Swipe on the right side for volume", null, Prefs.volumeGesture) { Prefs.volumeGesture = it }

            Spacer(Modifier.height(28.dp))
            Section("Library")
            ChoiceRow("Open the app on", Prefs.startScreen, StartScreen.entries, { it.label }) { Prefs.startScreen = it }
            SwitchRow(
                "Mark new videos",
                "Red NEW tags on videos you haven't played yet, and counts on their folders", Prefs.showNewTags,
            ) { Prefs.showNewTags = it }
            SwitchRow("Show thumbnails", "Off: faster on very large libraries", Prefs.showThumbnails) { Prefs.showThumbnails = it }

            Spacer(Modifier.height(28.dp))
            Section("Downloads")
            SwitchRow(
                "Download over Wi-Fi only",
                "On mobile data, downloads wait and carry on when you're back on Wi-Fi", Prefs.wifiOnly,
            ) { Prefs.wifiOnly = it }
            val speeds = listOf(0, 256, 512, 1024, 2048, 5120, 10240)
            ChoiceRow("Torrent download speed limit", Prefs.torrentDownloadLimitKb, speeds, ::speedLabel) {
                Prefs.torrentDownloadLimitKb = it
                TorrentEngine.applyLimits()
            }
            ChoiceRow("Torrent upload speed limit", Prefs.torrentUploadLimitKb, listOf(0, 64, 128, 256, 512, 1024), ::speedLabel) {
                Prefs.torrentUploadLimitKb = it
                TorrentEngine.applyLimits()
            }

            Spacer(Modifier.height(28.dp))
            Section("Updates")
            SwitchRow(
                "Check for updates automatically",
                "Every few hours, with a notification when a new version is out", Prefs.autoUpdateCheck,
            ) {
                Prefs.autoUpdateCheck = it
                if (it) UpdateWorker.schedule(context) else UpdateWorker.cancel(context)
            }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { scope.launch { Updates.check(userAsked = true) } }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Check for updates", fontWeight = FontWeight.Medium)
                    Text(
                        "All Media Player ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
            }

            Spacer(Modifier.height(28.dp))
            Section("Help")
            var reporting by remember { mutableStateOf(false) }
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { reporting = true }
                    .padding(vertical = 12.dp),
            ) {
                Column {
                    Text("Report a problem", fontWeight = FontWeight.Medium)
                    Text(
                        "Sends your description, device details and (if you allow) the app's recent log " +
                            "straight to the developer. You see everything before it's sent.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reporting) ReportDialog(Reports.Kind.PROBLEM, onDismiss = { reporting = false })
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(4.dp))
    HorizontalDivider()
    Spacer(Modifier.height(12.dp))
}
