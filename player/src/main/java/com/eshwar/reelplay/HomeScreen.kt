package com.eshwar.reelplay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.eshwar.reelplay.library.LibraryScreen
import com.eshwar.reelplay.report.ReportDialog
import com.eshwar.reelplay.report.Reports
import com.eshwar.reelplay.settings.Prefs
import com.eshwar.reelplay.settings.SettingsActivity
import com.eshwar.reelplay.settings.StartScreen
import com.eshwar.reelplay.torrent.DownloadsScreen
import com.eshwar.reelplay.torrent.TorrentDownloads
import com.eshwar.reelplay.ui.startActivitySafely
import com.eshwar.reelplay.update.Updates
import com.eshwar.reelplay.web.StreamDownloads
import kotlinx.coroutines.launch

/**
 * The two halves of the app, on a bottom bar: the Media Player (videos on the phone) and the
 * Downloader (web videos, streams and torrents). Opens on the one chosen in Settings.
 */
@Composable
fun HomeScreen(openDownloader: Boolean = false) {
    var tab by rememberSaveable {
        mutableStateOf(if (openDownloader || Prefs.startScreen == StartScreen.DOWNLOADER) 1 else 0)
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { StreamDownloads.load(context) } }
    val torrents by TorrentDownloads.items.collectAsState()
    val streams by StreamDownloads.items.collectAsState()
    val running = torrents.count { it.isActive } + streams.count { it.active }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Icon(Icons.Rounded.VideoLibrary, null) },
                    label = { Text("Media Player") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = {
                        // How many downloads are running, at a glance from the player side.
                        BadgedBox(badge = { if (running > 0) Badge { Text("$running") } }) {
                            Icon(Icons.Rounded.Download, null)
                        }
                    },
                    label = { Text("Downloader") },
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
            val none = WindowInsets(0, 0, 0, 0)
            if (tab == 0) LibraryScreen(insets = none) else DownloadsScreen(onClose = null, insets = none, menu = { AppMenu() })
        }
    }
}

/** Settings, problem reports and updates, for the Downloader tab's top bar. */
@Composable
private fun AppMenu() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "More") }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Settings") }, leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                onClick = { open = false; context.startActivitySafely(SettingsActivity.intent(context)) },
            )
            DropdownMenuItem(
                text = { Text("Report a problem") }, leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                onClick = { open = false; reporting = true },
            )
            DropdownMenuItem(
                text = { Text("Check for updates") }, leadingIcon = { Icon(Icons.Rounded.SystemUpdate, null) },
                onClick = { open = false; scope.launch { Updates.check(userAsked = true) } },
            )
        }
    }
    if (reporting) ReportDialog(Reports.Kind.PROBLEM, onDismiss = { reporting = false })
}
