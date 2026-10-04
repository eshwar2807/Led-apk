package com.eshwar.reelplay.torrent

import com.eshwar.reelplay.report.ReportDialog
import com.eshwar.reelplay.report.Reports
import androidx.compose.foundation.layout.Row
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Live diagnosis of a torrent's speed, refreshed every two seconds while shown. */
@Composable
fun SwarmHealthPanel(name: String, read: () -> SwarmHealth?) {
    val context = LocalContext.current
    var health by remember { mutableStateOf<SwarmHealth?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            health = withContext(Dispatchers.IO) { read() }
            delay(2000)
        }
    }
    val h = health
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(12.dp),
    ) {
        if (h == null) {
            Text("Reading torrent status…", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        val v = h.verdict
        Text(
            v.title,
            fontWeight = FontWeight.SemiBold,
            color = when (v.severity) {
                SwarmHealth.Severity.OK -> Color(0xFF66BB6A)
                SwarmHealth.Severity.WARN -> Color(0xFFFFB74D)
                SwarmHealth.Severity.BAD -> MaterialTheme.colorScheme.error
            },
        )
        Text(v.detail, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        val seeds = if (h.swarmSeeds < 0) "unknown" else h.swarmSeeds.toString()
        Text(
            "Connected ${h.connectedPeers} peers (${h.connectedSeeds} seeders) · in swarm $seeds seeders\n" +
                "Trackers ${h.trackersWorking} working / ${h.trackersTotal} · DHT ${h.dhtNodes} nodes · " +
                "incoming ${if (h.firewalled) "blocked" else "open"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        var reporting by remember { mutableStateOf(false) }
        Row {
            TextButton(onClick = { copy(context, h.report(name)) }) { Text("Copy details") }
            TextButton(onClick = { reporting = true }) { Text("Send to developer") }
        }
        if (reporting) {
            ReportDialog(
                Reports.Kind.TORRENT,
                details = h.report(name),
                title = "Report slow torrent",
                onDismiss = { reporting = false },
            )
        }
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("ReelPlay torrent diagnostics", text))
    Toast.makeText(context, "Copied. Paste it in a message to share.", Toast.LENGTH_SHORT).show()
}
