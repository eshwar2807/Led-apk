package com.eshwar.reelplay.web

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import com.eshwar.reelplay.ui.formatDuration
import com.eshwar.reelplay.ui.formatSize
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
    var choosing by remember { mutableStateOf<QualityChoice?>(null) }

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
                    "Paste a link to a web page and All Media Player lists the videos on it, ready to play or " +
                        "download. You can also share a page to All Media Player from your browser.",
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
                    val pageUrl = remember(s) { PageVideos.normalize(url) }
                    LazyColumn(
                        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(s.videos, key = { it.url }) { video ->
                            FoundTile(
                                video,
                                pageUrl,
                                onPlay = { play(context, video) },
                                onDownload = { info -> download(context, video, info, pageUrl) { choosing = it } },
                            )
                        }
                    }
                }
            }
        }
    }
    choosing?.let { c ->
        QualityDialog(c, onDismiss = { choosing = null }) { variant ->
            choosing = null
            startStream(context, c.video, c.pageUrl, variant.url, variant.audioUrl, variant.label)
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

/** A stream download waiting for the user to pick a quality. */
private class QualityChoice(val video: FoundVideo, val pageUrl: String, val info: VideoInfo)

@Composable
private fun FoundTile(video: FoundVideo, pageUrl: String, onPlay: () -> Unit, onDownload: (VideoInfo?) -> Unit) {
    val info by produceState(VideoProbe.cached(video.url), video.url) { value = VideoProbe.probe(video, pageUrl) }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(10.dp)) {
            Box(
                Modifier.width(144.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val frame = info?.frame
                when {
                    frame != null -> Image(
                        frame.asImageBitmap(), null,
                        contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                    )
                    info == null -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else -> Icon(
                        if (video.kind == FoundVideo.Kind.TORRENT || video.kind == FoundVideo.Kind.MAGNET) Icons.Rounded.Link
                        else Icons.Rounded.Movie,
                        null, tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val badge = if (info?.live == true) "LIVE" else info?.durationMs?.let { formatDuration(it) }
                badge?.let {
                    Text(
                        it, style = MaterialTheme.typography.labelSmall, color = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(if (info?.live == true) Color(0xCCE50914) else Color(0xAA000000), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(
                    details(video, info),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
                Text(
                    host(video.url) + if (video.label != null && video.label != video.name) " · ${video.name}" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onPlay, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Play")
                    }
                    if (canDownload(video, info)) {
                        FilledTonalButton(onClick = { onDownload(info) }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                            Icon(Icons.Rounded.Download, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Download")
                        }
                    }
                }
            }
        }
    }
}

private fun canDownload(video: FoundVideo, info: VideoInfo?): Boolean = when (video.kind) {
    FoundVideo.Kind.STREAM -> video.url.substringBefore('?').endsWith(".m3u8", true) && info?.live != true
    else -> true
}

private fun details(video: FoundVideo, info: VideoInfo?): String {
    val parts = mutableListOf<String>()
    info?.resolution?.let(parts::add)
    if (info != null && info.variants.size > 1) parts += "${info.variants.size} qualities"
    info?.sizeBytes?.let { parts += (if (info.sizeIsEstimate) "~" else "") + formatSize(it) }
    parts += when (video.kind) {
        FoundVideo.Kind.FILE -> video.name.substringAfterLast('.', "video").substringBefore('?').uppercase()
        FoundVideo.Kind.STREAM -> when {
            info?.live == true -> "Live stream (play only)"
            video.url.substringBefore('?').endsWith(".mpd", true) -> "DASH stream (play only)"
            else -> "HLS stream"
        }
        FoundVideo.Kind.TORRENT -> "Torrent"
        FoundVideo.Kind.MAGNET -> "Magnet link"
    }
    return parts.joinToString(" · ")
}

private fun host(url: String) = runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.") ?: "magnet"

@Composable
private fun QualityDialog(choice: QualityChoice, onDismiss: () -> Unit, onPick: (Hls.Variant) -> Unit) {
    val variants = choice.info.variants
    var picked by remember { mutableStateOf(variants.first()) }
    val seconds = (choice.info.durationMs ?: 0) / 1000
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Download quality") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(choice.video.title, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                variants.forEach { v ->
                    Row(Modifier.fillMaxWidth().clickable { picked = v }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = v == picked, onClick = { picked = v })
                        val size = if (v.bandwidth > 0 && seconds > 0) " · ~" + formatSize(v.bandwidth * seconds / 8) else ""
                        Text(v.label + size)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text("Download") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun play(context: Context, video: FoundVideo) {
    val intent = when (video.kind) {
        FoundVideo.Kind.TORRENT, FoundVideo.Kind.MAGNET -> TorrentActivity.intent(context, video.url)
        else -> PlayerActivity.intent(context, listOf(video.url.toUri()), listOf(video.title), 0)
    }
    context.startActivitySafely(intent)
}

private fun download(context: Context, video: FoundVideo, info: VideoInfo?, pageUrl: String, choose: (QualityChoice) -> Unit) {
    when (video.kind) {
        // The torrent screen has its own Download tab with file picking.
        FoundVideo.Kind.TORRENT, FoundVideo.Kind.MAGNET ->
            context.startActivitySafely(TorrentActivity.intent(context, video.url))
        FoundVideo.Kind.FILE -> try {
            WebDownloads.start(context, video.url, fileName(video), pageUrl)
            Toast.makeText(context, "Downloading to Download/ReelPlay. Progress and speed: the Downloader tab", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Couldn't start the download: ${e.message}", Toast.LENGTH_LONG).show()
        }
        FoundVideo.Kind.STREAM -> when {
            info == null -> Toast.makeText(context, "Still reading the stream; try again in a moment", Toast.LENGTH_SHORT).show()
            info.live -> Toast.makeText(context, "Live streams can't be downloaded", Toast.LENGTH_SHORT).show()
            info.variants.size > 1 -> choose(QualityChoice(video, pageUrl, info))
            else -> {
                val v = info.variants.firstOrNull()
                startStream(context, video, pageUrl, v?.url ?: video.url, v?.audioUrl, v?.label ?: (info.resolution ?: "Original"))
            }
        }
    }
}

/** A file name from the page's title for the video when it has one, keeping the file's extension. */
private fun fileName(video: FoundVideo): String {
    val ext = video.name.substringAfterLast('.', "mp4").substringBefore('?').take(5)
    val label = video.label ?: return video.name
    return if (label.endsWith(".$ext", true)) label else "$label.$ext"
}

private fun startStream(context: Context, video: FoundVideo, pageUrl: String, playlist: String, audio: String?, quality: String) {
    StreamDownloads.start(context, video.title, pageUrl, playlist, audio, quality)
    Toast.makeText(context, "Downloading $quality. Progress and speed: the Downloader tab", Toast.LENGTH_LONG).show()
}
