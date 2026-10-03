package com.eshwar.reelplay.torrent

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import com.eshwar.reelplay.player.PlayerActivity
import com.eshwar.reelplay.ui.ReelPlayTheme
import com.eshwar.reelplay.ui.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Takes a magnet link or .torrent file, lets you pick the video inside, buffers the first few
 * pieces and hands a `torrent://` URI to the player, which keeps downloading as it plays.
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
    onClose: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember {
        mutableStateOf<Phase>(if (source == null) Phase.Failed("No torrent or magnet link was given") else Phase.Resolving)
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
            val meta = withContext(Dispatchers.IO) { TorrentEngine.resolve(context, source) }
            val videos = meta.videoFiles
            if (videos.size == 1) {
                startFile(meta, videos.first())
                return@LaunchedEffect
            }
            Phase.Choose(meta)
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
                title = { Text("Stream torrent") },
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
                is Phase.Choose -> FileList(p.meta) { startFile(p.meta, it) }
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
private fun FileList(meta: TorrentMeta, onPick: (TorrentFile) -> Unit) {
    val videos = meta.videoFiles
    val shown = videos.ifEmpty { meta.files }
    Text(meta.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    Text(
        if (videos.isEmpty()) "No video files found — pick a file to try anyway"
        else "${videos.size} videos · pick one to play",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    LazyColumn {
        items(shown.sortedBy { it.path.lowercase() }, key = { it.index }) { file ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(file) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (file.isVideo) Icons.Rounded.Movie else Icons.AutoMirrored.Rounded.InsertDriveFile,
                    null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        formatSize(file.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BufferingView(stream: TorrentStream, onPlay: (TorrentStream) -> Unit, onClose: () -> Unit) {
    var progress by remember { mutableStateOf(0f) }
    var stats by remember { mutableStateOf<TorrentStats?>(null) }
    LaunchedEffect(stream) {
        while (true) {
            val ready = withContext(Dispatchers.IO) {
                progress = stream.startProgress()
                stats = stream.stats()
                stream.readyToPlay()
            }
            if (ready) {
                onPlay(stream)
                return@LaunchedEffect
            }
            delay(500)
        }
    }
    Centered {
        Text(stream.file.name, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(formatSize(stream.size), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Text("Buffering ${(progress * 100).toInt()}%")
        Spacer(Modifier.height(4.dp))
        Text(
            stats?.let { describe(it) } ?: "Connecting to peers…",
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

fun describe(s: TorrentStats): String =
    "↓ ${formatSize(s.downloadBytesPerSec.toLong())}/s · ${s.peers} peers (${s.seeds} seeds) · " +
        "${(s.progress * 100).toInt()}% downloaded"
