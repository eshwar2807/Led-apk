package com.eshwar.reelplay.torrent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.eshwar.reelplay.player.PlayerActivity
import com.eshwar.reelplay.ui.ReelPlayTheme
import com.eshwar.reelplay.web.RateMeter
import com.eshwar.reelplay.web.StreamDownload
import com.eshwar.reelplay.web.StreamDownloads
import com.eshwar.reelplay.web.WebDownload
import com.eshwar.reelplay.web.WebDownloads
import com.eshwar.reelplay.ui.formatDuration
import com.eshwar.reelplay.ui.formatSize
import com.eshwar.reelplay.ui.startActivitySafely
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Downloads: torrents (progress, pause/resume, play even mid-download, open, remove) and files
 * from "Find videos" (progress, speed, time left, play, cancel).
 */
class DownloadsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ReelPlayTheme { DownloadsScreen(onClose = ::finish) } }
    }

    companion object {
        fun intent(context: Context) = Intent(context, DownloadsActivity::class.java)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by TorrentDownloads.items.collectAsState()
    var engineError by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<DownloadItem?>(null) }
    var adding by remember { mutableStateOf(false) }

    // Downloads from "Find videos": polled once a second while this screen is open.
    var web by remember { mutableStateOf<List<WebDownload>>(emptyList()) }
    var webRates by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    val meters = remember { HashMap<Long, RateMeter>() }

    // Video stream downloads: progress comes from their worker; speed is measured here.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { StreamDownloads.load(context) } }
    val streams by StreamDownloads.items.collectAsState()
    var streamRates by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    val streamMeters = remember { HashMap<String, RateMeter>() }
    LaunchedEffect(Unit) {
        while (true) {
            val now = SystemClock.elapsedRealtime()
            streamRates = StreamDownloads.items.value.associate { d ->
                d.id to if (d.state == StreamDownload.State.DOWNLOADING) {
                    streamMeters.getOrPut(d.id) { RateMeter() }.sample(d.bytes, now) ?: 0L
                } else {
                    streamMeters.remove(d.id)
                    0L
                }
            }
            delay(1000)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            val list = withContext(Dispatchers.IO) { WebDownloads.list(context) }
            val now = SystemClock.elapsedRealtime()
            webRates = list.associate { d ->
                val rate = if (d.status == WebDownload.Status.DOWNLOADING) {
                    meters.getOrPut(d.id) { RateMeter() }.sample(d.doneBytes, now) ?: 0L
                } else {
                    meters.remove(d.id)
                    0L
                }
                d.id to rate
            }
            web = list
            delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        try {
            withContext(Dispatchers.IO) { TorrentDownloads.restore(context) }
            if (TorrentDownloads.hasActive()) TorrentDownloadService.start(context)
        } catch (_: LinkageError) {
            engineError = "The torrent engine isn't available on this device's processor."
        }
    }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()

    fun play(item: DownloadItem, fileIndex: Int?, saved: SavedFile?) {
        if (saved != null) {
            context.startActivitySafely(PlayerActivity.intent(context, listOf(saved.uri.toUri()), listOf(saved.name), 0))
            return
        }
        val index = fileIndex ?: return
        scope.launch {
            try {
                val stream = withContext(Dispatchers.IO) { TorrentDownloads.streamFile(context, item.id, index) }
                TorrentDownloadService.start(context)
                context.startActivitySafely(
                    PlayerActivity.intent(context, listOf(TorrentEngine.uriFor(stream)), listOf(stream.file.name), 0),
                )
            } catch (e: Exception) {
                toast(e.message ?: "Couldn't play that")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = { Text("Downloads") },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, "Add torrent") } },
            )
        },
    ) { padding ->
        when {
            engineError != null && web.isEmpty() && streams.isEmpty() -> Message(padding, engineError!!)
            items.isEmpty() && web.isEmpty() && streams.isEmpty() -> Message(
                padding,
                "No downloads yet.\nTap + to add a magnet link or .torrent file, or open one from your browser or files.",
            )
            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = 16.dp, end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp, bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(streams, key = { "stream-${it.id}" }) { d ->
                    StreamDownloadCard(
                        d,
                        rate = streamRates[d.id] ?: 0L,
                        onPlay = {
                            d.savedUri?.let {
                                context.startActivitySafely(PlayerActivity.intent(context, listOf(it.toUri()), listOf(d.title), 0))
                            }
                        },
                        onCancel = { scope.launch(Dispatchers.IO) { StreamDownloads.cancel(context, d.id) } },
                        onRetry = { StreamDownloads.retry(context, d.id) },
                        onRemove = { scope.launch(Dispatchers.IO) { StreamDownloads.remove(context, d.id) } },
                    )
                }
                items(web, key = { "web-${it.id}" }) { d ->
                    WebDownloadCard(
                        d,
                        rate = webRates[d.id] ?: 0L,
                        onPlay = {
                            val uri = WebDownloads.fileUri(context, d.id)
                            if (uri == null) {
                                toast("The file is gone")
                            } else {
                                context.startActivitySafely(PlayerActivity.intent(context, listOf(uri), listOf(d.title), 0))
                            }
                        },
                        onRemove = { deleteFile ->
                            scope.launch(Dispatchers.IO) { WebDownloads.remove(context, d.id, deleteFile) }
                            web = web.filter { it.id != d.id }
                        },
                    )
                }
                items(items, key = { it.id }) { item ->
                    DownloadCard(
                        item = item,
                        onPause = { scope.launch(Dispatchers.IO) { TorrentDownloads.pause(item.id) } },
                        onResume = {
                            scope.launch(Dispatchers.IO) { TorrentDownloads.resume(item.id) }
                            TorrentDownloadService.start(context)
                        },
                        onPlay = { index, saved -> play(item, index, saved) },
                        onOpen = { saved -> open(context, saved) },
                        onShare = { saved -> share(context, saved) },
                        onRemove = { removing = item },
                    )
                }
            }
        }
    }

    removing?.let { item ->
        var deleteSaved by remember(item.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove download?") },
            text = {
                Column {
                    Text(item.record.name, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (item.record.done && item.record.saved.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = deleteSaved, onCheckedChange = { deleteSaved = it })
                            Text("Also delete the saved files")
                        }
                    } else if (!item.record.done) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "What's downloaded so far will be deleted.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = item.id
                    removing = null
                    scope.launch(Dispatchers.IO) { TorrentDownloads.remove(id, deleteSaved) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }

    if (adding) {
        TorrentSourceDialog(
            onDismiss = { adding = false },
            onOpen = { source ->
                adding = false
                context.startActivitySafely(TorrentActivity.intent(context, source))
            },
        )
    }
}

@Composable
private fun StreamDownloadCard(
    d: StreamDownload,
    rate: Long,
    onPlay: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val estimate = d.estimatedBytes
            val size = when {
                d.state == StreamDownload.State.DONE || estimate == null -> formatSize(d.bytes)
                else -> "${formatSize(d.bytes)} of ~${formatSize(estimate)}"
            }
            Text(
                "${d.state.label} · ${d.quality} · $size",
                style = MaterialTheme.typography.bodySmall,
                color = if (d.state == StreamDownload.State.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d.active) {
                Spacer(Modifier.height(8.dp))
                val p = d.progress
                if (p == null || d.state != StreamDownload.State.DOWNLOADING) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val line = when (d.state) {
                    StreamDownload.State.DOWNLOADING -> {
                        val eta = if (rate > 0 && estimate != null) {
                            " · ${formatDuration((estimate - d.bytes).coerceAtLeast(0) * 1000 / rate)} left"
                        } else ""
                        "${((p ?: 0f) * 100).toInt()}% · ↓ ${formatSize(rate)}/s · part ${d.donePieces} of ${d.totalPieces}$eta"
                    }
                    StreamDownload.State.QUEUED -> d.error ?: "Waiting for a connection"
                    else -> null
                }
                line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (d.state == StreamDownload.State.FAILED) {
                d.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            if (d.state == StreamDownload.State.DONE) {
                d.location?.let {
                    Text("Saved to $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                when {
                    d.active -> TextButton(onClick = onCancel) { Text("Cancel") }
                    d.state == StreamDownload.State.DONE -> {
                        TextButton(onClick = onRemove) { Text("Remove from list") }
                        TextButton(onClick = onPlay) { Text("Play") }
                    }
                    else -> {
                        TextButton(onClick = onRemove) { Text("Remove") }
                        TextButton(onClick = onRetry) { Text("Retry") }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebDownloadCard(d: WebDownload, rate: Long, onPlay: () -> Unit, onRemove: (deleteFile: Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(d.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val size = when {
                d.status == WebDownload.Status.DONE -> formatSize(d.doneBytes)
                d.totalBytes > 0 -> "${formatSize(d.doneBytes)} of ${formatSize(d.totalBytes)}"
                else -> formatSize(d.doneBytes)
            }
            Text(
                "${d.status.label} · $size · from the web",
                style = MaterialTheme.typography.bodySmall,
                color = if (d.status == WebDownload.Status.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (d.active) {
                Spacer(Modifier.height(8.dp))
                val p = d.progress
                if (p == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                else LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                val eta = if (rate > 0 && d.totalBytes > 0) {
                    " · ${formatDuration((d.totalBytes - d.doneBytes).coerceAtLeast(0) * 1000 / rate)} left"
                } else ""
                val percent = p?.let { "${(it * 100).toInt()}% · " }.orEmpty()
                Text(
                    "$percent↓ ${formatSize(rate)}/s$eta",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            d.problem?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (d.status == WebDownload.Status.FAILED) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                when {
                    d.status == WebDownload.Status.DONE -> {
                        TextButton(onClick = { onRemove(false) }) { Text("Remove from list") }
                        TextButton(onClick = onPlay) { Text("Play") }
                    }
                    d.active -> TextButton(onClick = { onRemove(true) }) { Text("Cancel") }
                    else -> TextButton(onClick = { onRemove(true) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun Message(padding: PaddingValues, text: String) {
    Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onPlay: (Int?, SavedFile?) -> Unit,
    onOpen: (SavedFile) -> Unit,
    onShare: (SavedFile) -> Unit,
    onRemove: () -> Unit,
) {
    val r = item.record
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            val sizeText = if (r.done) formatSize(r.totalBytes) else "${formatSize(item.doneBytes)} of ${formatSize(r.totalBytes)}"
            Text(
                "${item.state.label} · $sizeText · ${r.selected.size} file${if (r.selected.size == 1) "" else "s"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (item.state == DownloadState.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!r.done) {
                Spacer(Modifier.height(8.dp))
                if (item.state == DownloadState.STARTING || item.state == DownloadState.SAVING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { item.progress }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                val eta = item.etaSeconds?.let { " · ${formatDuration(it * 1000)} left" }.orEmpty()
                val stats = when (item.state) {
                    DownloadState.DOWNLOADING ->
                        "${(item.progress * 100).toInt()}% · ↓ ${formatSize(item.downloadRate.toLong())}/s · " +
                            "↑ ${formatSize(item.uploadRate.toLong())}/s · ${item.peers} peers (${item.seeds} seeders)$eta"
                    DownloadState.CHECKING -> "Checking what's already downloaded · ${(item.progress * 100).toInt()}%"
                    DownloadState.STARTING ->
                        "Finding peers · ${item.peers} connected (${item.seeds} seeders)" +
                            if (item.downloadRate > 0) " · ↓ ${formatSize(item.downloadRate.toLong())}/s" else ""
                    DownloadState.SAVING -> "Copying to Download/ReelPlay…"
                    DownloadState.PAUSED -> "Paused at ${(item.progress * 100).toInt()}%"
                    else -> null
                }
                stats?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                r.location?.let {
                    Text("Saved to $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            r.error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (item.state == DownloadState.DOWNLOADING || item.state == DownloadState.STARTING) {
                var diagnose by remember { mutableStateOf(false) }
                TextButton(onClick = { diagnose = !diagnose }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (diagnose) "Hide details" else "Why is it slow?")
                }
                if (diagnose) SwarmHealthPanel(r.name) { TorrentDownloads.health(item.id) }
            }

            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                // Play: finished copies, or stream a video straight out of the unfinished download.
                val savedVideos = r.saved.filter { it.isVideo }
                val liveVideos = if (r.done) emptyList() else item.files.filter { it.isVideo }
                if (savedVideos.isNotEmpty() || liveVideos.isNotEmpty()) {
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = {
                            when {
                                savedVideos.size == 1 -> onPlay(null, savedVideos.first())
                                liveVideos.size == 1 && savedVideos.isEmpty() -> onPlay(liveVideos.first().index, null)
                                else -> menu = true
                            }
                        }) {
                            Icon(Icons.Rounded.PlayArrow, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            savedVideos.forEach { f ->
                                DropdownMenuItem(text = { Text(f.name) }, onClick = { menu = false; onPlay(null, f) })
                            }
                            liveVideos.forEach { f ->
                                DropdownMenuItem(text = { Text(f.name) }, onClick = { menu = false; onPlay(f.index, null) })
                            }
                        }
                    }
                }
                if (r.done && r.saved.isNotEmpty()) {
                    if (savedVideos.size < r.saved.size) {
                        IconButton(onClick = { onOpen(r.saved.first { !it.isVideo }) }) {
                            Icon(Icons.Rounded.FolderOpen, "Open")
                        }
                    }
                    IconButton(onClick = { onShare(r.saved.first()) }) { Icon(Icons.Rounded.Share, "Share") }
                }
                when (item.state) {
                    DownloadState.PAUSED -> IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, "Resume") }
                    DownloadState.FAILED -> IconButton(onClick = onResume) { Icon(Icons.Rounded.Refresh, "Retry") }
                    DownloadState.DOWNLOADING, DownloadState.CHECKING, DownloadState.STARTING ->
                        IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, "Pause") }
                    else -> Unit
                }
                if (item.state != DownloadState.SAVING) {
                    IconButton(onClick = onRemove) { Icon(Icons.Rounded.Delete, "Remove") }
                }
            }
        }
    }
}

private fun open(context: Context, file: SavedFile) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri.toUri(), file.mime)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivitySafely(Intent.createChooser(view, "Open ${file.name}"), "Couldn't open ${file.name}")
}

private fun share(context: Context, file: SavedFile) {
    val send = Intent(Intent.ACTION_SEND).setType(file.mime)
        .putExtra(Intent.EXTRA_STREAM, file.uri.toUri())
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivitySafely(Intent.createChooser(send, "Share ${file.name}"), "Couldn't share")
}
