package com.eshwar.reelplay.torrent

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Asks for a magnet link, a .torrent URL or a .torrent file. [onOpen] gets it as a string. */
@Composable
fun TorrentSourceDialog(onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpen(uri.toString())
    }
    val valid = link.startsWith("magnet:", ignoreCase = true) ||
        link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open torrent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it.trim() },
                    label = { Text("Magnet link or .torrent URL") },
                    placeholder = { Text("magnet:?xt=urn:btih:…") },
                    singleLine = true,
                )
                TextButton(onClick = {
                    filePicker.launch(arrayOf("application/x-bittorrent", "application/octet-stream", "*/*"))
                }) {
                    Icon(Icons.Rounded.Folder, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose a .torrent file")
                }
                Text(
                    "Next you can stream a video straight away or download files to keep. " +
                        "Only share content you have the right to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(link) }, enabled = valid) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
