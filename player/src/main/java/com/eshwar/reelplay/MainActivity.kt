package com.eshwar.reelplay

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.eshwar.reelplay.library.LibraryScreen
import com.eshwar.reelplay.torrent.TorrentDownloadService
import com.eshwar.reelplay.torrent.TorrentDownloads
import com.eshwar.reelplay.ui.ReelPlayTheme
import com.eshwar.reelplay.update.UpdateDialog
import com.eshwar.reelplay.update.UpdateWorker
import com.eshwar.reelplay.update.Updates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The video library: folders, all videos, and the way into the editor. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ReelPlayTheme {
                LibraryScreen()
                CrashReportPrompt()
                UpdateDialog()
                AskForNotifications()
            }
        }
        checkForUpdate(fromNotification = intent.getBooleanExtra(UpdateWorker.EXTRA_SHOW_UPDATE, false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(UpdateWorker.EXTRA_SHOW_UPDATE, false)) checkForUpdate(fromNotification = true)
    }

    /** Quiet check on launch: only speaks up if there's a version the user hasn't waved off. */
    private fun checkForUpdate(fromNotification: Boolean) {
        lifecycleScope.launch {
            val update = try {
                Updates.fetch()
            } catch (_: Exception) {
                null
            } ?: return@launch
            if (fromNotification || !Updates.wasDismissed(this@MainActivity, update)) Updates.showAvailable(update)
        }
    }

    override fun onResume() {
        super.onResume()
        // If the app was relaunched with downloads unfinished, the background service may
        // not have been allowed to start from the restore thread; now we're visible, it is.
        if (TorrentDownloads.hasActive()) TorrentDownloadService.start(this)
    }
}

/** After a crash, says what happened and offers to share the details. */
@Composable
private fun CrashReportPrompt() {
    val context = LocalContext.current
    var report by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        report = withContext(Dispatchers.IO) { CrashReporter.pending(context) }
    }
    val text = report ?: return
    fun close() {
        CrashReporter.dismiss(context)
        report = null
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text("ReelPlay stopped last time") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Sharing this report helps find the cause. Any torrent downloads that were " +
                        "running have been paused; resume them from Downloads.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Text(text, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "ReelPlay crash report")
                    .putExtra(Intent.EXTRA_TEXT, text)
                context.startActivity(Intent.createChooser(send, "Share crash report"))
                close()
            }) { Text("Share report") }
        },
        dismissButton = { TextButton(onClick = ::close) { Text("Close") } },
    )
}

/** Asks once (Android 13+) so update and download notifications can be shown. */
@Composable
private fun AskForNotifications() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("onboarding", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return@LaunchedEffect
        prefs.edit { putBoolean("asked_notifications", true) }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
