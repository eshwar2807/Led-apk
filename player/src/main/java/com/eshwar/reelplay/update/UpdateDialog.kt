package com.eshwar.reelplay.update

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.eshwar.reelplay.BuildConfig
import com.eshwar.reelplay.ui.formatSize
import kotlinx.coroutines.launch

/** Shows whatever [Updates] is doing: offer, download progress, install, or what went wrong. */
@Composable
fun UpdateDialog() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by Updates.state.collectAsState()

    // Back from the "Install unknown apps" settings screen: carry on if it's now allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val s = Updates.state.value
        if (s is UpdateState.NeedsPermission && context.packageManager.canRequestPackageInstalls()) {
            scope.launch { Updates.install(context, s.update) }
        }
    }

    fun close() = Updates.dismiss(context)

    when (val s = state) {
        UpdateState.Idle -> Unit
        UpdateState.Checking -> AlertDialog(
            onDismissRequest = ::close,
            title = { Text("Checking for updates…") },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {},
        )
        UpdateState.UpToDate -> AlertDialog(
            onDismissRequest = ::close,
            title = { Text("You're up to date") },
            text = { Text("ReelPlay ${BuildConfig.VERSION_NAME} is the latest version.") },
            confirmButton = { TextButton(onClick = ::close) { Text("OK") } },
        )
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = ::close,
            title = { Text("ReelPlay ${s.update.versionName} is available") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        "You have ${BuildConfig.VERSION_NAME}. Download size ${formatSize(s.update.size)}.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (s.update.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(s.update.notes)
                    }
                }
            },
            confirmButton = {
                Button(onClick = { scope.launch { Updates.install(context, s.update) } }) { Text("Update") }
            },
            dismissButton = { TextButton(onClick = ::close) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading ${s.update.versionName}…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text("${(s.progress * 100).toInt()}% of ${formatSize(s.update.size)}")
                }
            },
            confirmButton = {},
        )
        is UpdateState.NeedsPermission -> AlertDialog(
            onDismissRequest = ::close,
            title = { Text("Allow ReelPlay to install updates") },
            text = {
                Text(
                    "Android asks once before an app can install its own updates. Turn on " +
                        "\"Allow from this source\" for ReelPlay, then come back.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri()),
                    )
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = ::close) { Text("Cancel") } },
        )
        is UpdateState.Installing -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Installing ${s.update.versionName}…") },
            text = { Text("Confirm on the next screen. ReelPlay restarts when it's done.") },
            confirmButton = {},
        )
        is UpdateState.Failed -> AlertDialog(
            onDismissRequest = ::close,
            title = { Text("Update problem") },
            text = { Text(s.message) },
            confirmButton = {
                if (s.update != null) {
                    TextButton(onClick = { scope.launch { Updates.install(context, s.update) } }) { Text("Try again") }
                } else {
                    TextButton(onClick = ::close) { Text("OK") }
                }
            },
            dismissButton = if (s.update != null) ({ TextButton(onClick = ::close) { Text("Close") } }) else null,
        )
    }
}
