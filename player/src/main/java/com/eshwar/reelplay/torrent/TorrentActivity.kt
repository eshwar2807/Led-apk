package com.eshwar.reelplay.torrent

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.eshwar.reelplay.player.PlayerActivity
import com.eshwar.reelplay.ui.ReelPlayTheme
import com.eshwar.reelplay.ui.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Takes a magnet link or .torrent file and offers two things: stream a video from it (buffer
 * the first few pieces, then hand a `torrent://` URI to the player, which keeps downloading as
 * it plays), or download chosen files to keep via [TorrentDownloads].
 */
class TorrentActivity : ComponentActivity() {

    private var handedToPlayer = false
    private var stream: TorrentStream? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val source = sourceFrom(intent)
        setContent {
            ReelPlayTheme {
                TorrentScreen(
                    source = source,
                    onStream = { stream = it },
                    onPlay = ::play,
                    onDownloaded = {
                        startActivity(DownloadsActivity.intent(this))
                        finish()
                    },
                    onClose = ::finish,
                )
            }
        }
    }

    private fun sourceFrom(intent: Intent): String? {
        intent.getStringExtra(EXTRA_SOURCE)?.let { return it }
        return when (intent.action) {
            Intent.ACTION_SEND -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, android.net.Uri::class.java)?.toString()
                    ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                        Regex("magnet:\\?\\S+", RegexOption.IGNORE_CASE).find(text)?.value
                    }
            }
            else -> intent.data?.toString()
        }
    }

    private fun play(stream: TorrentStream) {
        handedToPlayer = true
        startActivity(
            PlayerActivity.intent(this, listOf(TorrentEngine.uriFor(stream)), listOf(stream.file.name), 0),
        )
        finish()
    }

    override fun onDestroy() {
        // Backed out before playing: nothing will read this torrent, so stop it.
        if (!handedToPlayer) stream?.let { TorrentEngine.close(TorrentEngine.uriFor(it)) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SOURCE = "source"

        /** [source] is a magnet URI, an http(s) link to a .torrent, or a content URI of one. */
        fun intent(context: Context, source: String): Intent =
            Intent(context, TorrentActivity::class.java).putExtra(EXTRA_SOURCE, source)
    }
}

private sealed interface Phase {
    data object Resolving : Phase
    data class Failed(val message: String) : Phase
    data class Choose(val meta: TorrentMeta) : Phase
    data class Buffering(val stream: TorrentStream) : Phase
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TorrentScreen(
    source: String?,
    onStream: (TorrentStream) -> Unit,
    onPlay: (TorrentStream) -> Unit,
    onDownloaded: () -> Unit,
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember {
        mutableStateOf<Phase>(if (source == null) Phase.Failed("No torrent or magnet link was given") else Phase.Resolving)
    }

    var pendingDownload by remember { mutableStateOf<Pair<TorrentMeta, Set<Int>>?>(null) }

    fun download(meta: TorrentMeta, selected: Set<Int>) {
        phase = Phase.Resolving
        scope.launch {
            try {
                withContext(Dispatchers.IO) { TorrentDownloads.add(context, meta, selected) }
                TorrentDownloadService.start(context)
                onDownloaded()
            } catch (e: Exception) {
                phase = Phase.Failed(e.message ?: "Couldn't start the download")
            }
        }
    }

    // Asked once, when the first download starts: notifications to show progress, and on
    // Android 8-9 storage access to save into Download/.
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        pendingDownload?.let { (meta, selected) -> download(meta, selected) }
        pendingDownload = null
    }

    fun requestDownload(meta: TorrentMeta, selected: Set<Int>) {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) {
            download(meta, selected)
        } else {
            pendingDownload = meta to selected
            permissions.launch(needed.toTypedArray())
        }
    }

    fun startFile(meta: TorrentMeta, file: TorrentFile) {
        phase = Phase.Resolving
        scope.launch {
            phase = try {
                val stream = withContext(Dispatchers.IO) { TorrentEngine.start(context, meta, file) }
                onStream(stream)
                Phase.Buffering(stream)
            } catch (e: Exception) {
                Phase.Failed(e.message ?: "Couldn't start the torrent")
            }
        }
    }

    LaunchedEffect(source) {
        if (source == null) return@LaunchedEffect
        phase = try {
            Phase.Choose(withContext(Dispatchers.IO) { TorrentEngine.resolve(context, source) })
        } catch (e: Exception) {
            Phase.Failed(e.message ?: "Couldn't read this torrent")
        } catch (_: LinkageError) {
            Phase.Failed("The torrent engine isn't available on this device's processor")
        }
    }

    BackHandler { onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                },
                title = { Text("Torrent") },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            when (val p = phase) {
                Phase.Resolving -> Centered {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                    Text(
                        if (source?.startsWith("magnet:", true) == true) "Asking peers for the torrent's details…"
                        else "Reading torrent…",
                    )
                }
                is Phase.Failed -> Centered {
                    Text(p.message, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onClose) { Text("Close") }
                }
                is Phase.Choose -> ChooseView(
                    meta = p.meta,
                    onStream = { startFile(p.meta, it) },
                    onDownload = { requestDownload(p.meta, it) },
                )
                is Phase.Buffering -> BufferingView(p.stream, onPlay, onClose)
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content() }
}

@Composable
private fun ChooseView(meta: TorrentMeta, onStream: (TorrentFile) -> Unit, onDownload: (Set<Int>) -> Unit) {
    val videos = meta.videoFiles
    var tab by remember { mutableIntStateOf(if (videos.isEmpty()) 1 else 0) }
    var selected by remember { mutableStateOf(meta.files.map { it.index }.toSet()) }
    val files = remember(meta) { meta.files.sortedBy { it.path.lowercase() } }

    Column(Modifier.fillMaxSize()) {
        Text(meta.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "${meta.files.size} file${if (meta.files.size == 1) "" else "s"} · ${formatSize(meta.files.sumOf { it.size })}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        SecondaryTabRow(selectedTabIndex = tab) {
            Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Stream") })
            Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Download") })
        }
        if (tab == 0) {
            Text(
                if (videos.isEmpty()) "No video files in this torrent. Download it instead."
                else "Tap a video to start watching while it downloads. Nothing is kept afterwards.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(files.filter { it.isVideo }, key = { it.index }) { file ->
                    FileRow(file, onClick = { onStream(file) })
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Choose what to keep. Saved to Download/All Media Player when done.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    selected = if (selected.size == meta.files.size) emptySet() else meta.files.map { it.index }.toSet()
                }) { Text(if (selected.size == meta.files.size) "None" else "All") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(files, key = { it.index }) { file ->
                    val checked = file.index in selected
                    val toggle = { selected = if (checked) selected - file.index else selected + file.index }
                    FileRow(file, onClick = toggle) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                    }
                }
            }
            val size = meta.files.filter { it.index in selected }.sumOf { it.size }
            Button(
                onClick = { onDownload(selected) },
                enabled = selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Download ${selected.size} file${if (selected.size == 1) "" else "s"} · ${formatSize(size)}")
            }
        }
    }
}

@Composable
private fun FileRow(file: TorrentFile, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (file.isVideo) Icons.Rounded.Movie else Icons.AutoMirrored.Rounded.InsertDriveFile,
            null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // Show folders inside the torrent, minus the torrent's own top folder.
            Text(file.path.substringAfter('/', file.path), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                formatSize(file.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailing?.invoke()
    }
}

/**
 * Waits until playing can start and then go to the end without stopping: the file's start and
 * end (container header and index) must be here, plus enough of the beginning that the
 * download stays ahead of playback at the speed it's actually running (see [StreamReadiness]).
 * Then it starts by itself. "Play now" is always there for the impatient.
 */
@Composable
private fun BufferingView(stream: TorrentStream, onPlay: (TorrentStream) -> Unit, onClose: () -> Unit) {
    var stats by remember { mutableStateOf<TorrentStats?>(null) }
    var plan by remember { mutableStateOf<StreamReadiness.Plan?>(null) }
    var contiguous by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(stream) {
        val rates = ArrayDeque<Int>()
        var probed = false
        while (true) {
            val s = withContext(Dispatchers.IO) { stream.stats() }
            val have = withContext(Dispatchers.IO) { stream.contiguousBytes() }
            val endsReady = withContext(Dispatchers.IO) { stream.endsReady() }
            error = withContext(Dispatchers.IO) { stream.error() }
            stats = s
            contiguous = have
            // Average over ~15 s: swarm speed jumps around too much second to second.
            s?.let { rates.addLast(it.downloadBytesPerSec); if (rates.size > 15) rates.removeFirst() }
            val rate = if (rates.isEmpty()) 0.0 else rates.average()
            if (endsReady && !probed) {
                probed = true
                durationMs = probeDuration(stream)
            }
            val p = StreamReadiness.plan(stream.size, durationMs, have, rate)
            plan = p
            if (endsReady && p.ready && error == null) {
                onPlay(stream)
                return@LaunchedEffect
            }
            delay(1000)
        }
    }

    Centered {
        Text(stream.file.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            formatSize(stream.size) + (durationMs?.let { " · ${com.eshwar.reelplay.ui.formatDuration(it)}" } ?: ""),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        val p = plan
        val fraction = if (p == null || p.neededBytes <= 0) 0f else (contiguous.toFloat() / p.neededBytes).coerceIn(0f, 1f)
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                error != null -> "Torrent error: $error"
                p == null -> "Connecting to peers…"
                p.etaSeconds == null -> "Waiting for peers to send data…"
                else -> "Ready to play without stopping in about ${com.eshwar.reelplay.ui.formatDuration(p.etaSeconds * 1000)}"
            },
            fontWeight = FontWeight.Medium,
            color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(6.dp))
        if (p != null) {
            Text(
                "Buffered ${formatSize(contiguous)} of ${formatSize(p.neededBytes)} needed · video plays at " +
                    "${formatSize(p.bitrate.toLong())}/s${if (p.bitrateGuessed) " (estimated)" else ""}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (p.fastEnough) "Downloading faster than it plays — only a short buffer needed."
                else "Downloading slower than it plays, so more is buffered first to avoid pauses later.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // After a while with no start, say why rather than leave a bare progress bar.
        var waited by remember { mutableIntStateOf(0) }
        LaunchedEffect(stream) {
            while (true) {
                delay(1000)
                waited++
            }
        }
        if (waited >= 20) SwarmHealthPanel(stream.file.name) { SwarmHealth.of(stream.handle) }
        Spacer(Modifier.height(4.dp))
        Text(
            stats?.let { describe(it) } ?: "",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onClose) { Text("Cancel") }
            Button(onClick = { onPlay(stream) }) { Text("Play now") }
        }
    }
}

/**
 * The video's length, read from the partly downloaded file once its header and end are in.
 * Gives the real bitrate instead of a guess. Null if the container can't be read yet.
 */
private suspend fun probeDuration(stream: TorrentStream): Long? =
    kotlinx.coroutines.withTimeoutOrNull(8_000) {
        kotlinx.coroutines.runInterruptible(Dispatchers.IO) {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(stream.path.absolutePath)
                r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 }
            } catch (_: Exception) {
                null
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        }
    }

fun describe(s: TorrentStats): String =
    "↓ ${formatSize(s.downloadBytesPerSec.toLong())}/s · ${s.peers} peers (${s.seeds} seeds) · " +
        "${(s.progress * 100).toInt()}% downloaded"
