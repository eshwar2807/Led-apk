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

            Spacer(Modifier.height(28.dp))
            Section("Updates")
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .clickable { scope.launch { Updates.check(userAsked = true) } }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Check for updates", fontWeight = FontWeight.Medium)
                    Text(
                        "ReelPlay ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}). Also checked " +
                            "automatically every few hours, with a notification when one is out.",
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
