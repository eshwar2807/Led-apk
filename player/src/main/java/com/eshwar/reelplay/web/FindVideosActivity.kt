package com.eshwar.reelplay.web

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.eshwar.reelplay.player.PlayerActivity
import com.eshwar.reelplay.torrent.TorrentActivity
import com.eshwar.reelplay.ui.ReelPlayTheme
import com.eshwar.reelplay.ui.startActivitySafely
import kotlinx.coroutines.launch

/**
 * "Find videos on a page": give it a web page (typed, pasted, or shared from the browser) and
 * it lists the videos on that page, each with Play and Download.
 */
class FindVideosActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val shared = intent?.getStringExtra(Intent.EXTRA_TEXT)?.let(::firstLink) ?: intent?.dataString
        setContent { ReelPlayTheme { FindVideosScreen(initialUrl = shared.orEmpty(), onClose = ::finish) } }
    }

    companion object {
        fun intent(context: Context) = Intent(context, FindVideosActivity::class.java)

        /** Shared text is often "Title https://…"; take the link out of it. */
        internal fun firstLink(text: String): String =
            Regex("""(https?://|magnet:\?)\S+""", RegexOption.IGNORE_CASE).find(text)?.value ?: text.trim()
    }
}

private sealed interface ScanState {
    data object Idle : ScanState
    data object Scanning : ScanState
    data class Found(val videos: List<FoundVideo>) : ScanState
    data class Failed(val message: String) : ScanState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindVideosScreen(initialUrl: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var state by remember { mutableStateOf<ScanState>(ScanState.Idle) }

    fun scan() {
        if (url.isBlank()) return
        state = ScanState.Scanning
        scope.launch {
            state = try {
                ScanState.Found(PageVideos.scan(url))
            } catch (e: Exception) {
                ScanState.Failed(e.message ?: "Couldn't load that page")
            }
        }
    }

    LaunchedEffect(Unit) { if (initialUrl.isNotBlank()) scan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find videos on a page") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Web page link") },
                    placeholder = { Text("https://…") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { scan() }),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(onClick = ::scan, enabled = state != ScanState.Scanning && url.isNotBlank()) {
                    Icon(Icons.Rounded.Search, null)
                }
            }
            Spacer(Modifier.padding(4.dp))
            when (val s = state) {
                ScanState.Idle -> Hint(
                    "Paste a link to a web page and ReelPlay lists the videos on it, ready to play or " +
                        "download. You can also share a page to ReelPlay from your browser.",
                )
                ScanState.Scanning -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                is ScanState.Failed -> Hint(s.message, error = true)
                is ScanState.Found -> if (s.videos.isEmpty()) {
                    Hint(
                        "No videos found on that page. Some sites load their player with scripts, " +
                            "so their videos don't appear in the page itself.",
                    )
                } else {
                    Text(
                        "${s.videos.size} video${if (s.videos.size == 1) "" else "s"} found",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                        items(s.videos, key = { it.url }) { video ->
                            FoundRow(
                                video,
                                onPlay = { play(context, video) },
                                onDownload = { download(context, video) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun FoundRow(video: FoundVideo, onPlay: () -> Unit, onDownload: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
        Text(
            "${video.kind.label} · ${video.url}",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(4.dp))
                Text("Play")
            }
            if (video.downloadable) {
                FilledTonalButton(onClick = onDownload) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(4.dp))
                    Text("Download")
                }
            }
        }
    }
}

private fun play(context: Context, video: FoundVideo) {
    val intent = when (video.kind) {
        FoundVideo.Kind.TORRENT, FoundVideo.Kind.MAGNET -> TorrentActivity.intent(context, video.url)
        else -> PlayerActivity.intent(context, listOf(video.url.toUri()), listOf(video.name), 0)
    }
    context.startActivitySafely(intent)
}

private fun download(context: Context, video: FoundVideo) {
    when (video.kind) {
        // The torrent screen has its own Download tab with file picking.
        FoundVideo.Kind.TORRENT, FoundVideo.Kind.MAGNET ->
            context.startActivitySafely(TorrentActivity.intent(context, video.url))
        FoundVideo.Kind.FILE -> try {
            WebDownloads.start(context, video.url, video.name)
            Toast.makeText(context, "Downloading to Download/ReelPlay. Progress and speed: ⋮ → Downloads", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't start the download: ${e.message}", Toast.LENGTH_LONG).show()
        }
        FoundVideo.Kind.STREAM -> Unit
    }
}
