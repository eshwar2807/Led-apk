package com.eshwar.reelplay.library

import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.runtime.collectAsState
import com.eshwar.reelplay.settings.Prefs
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.eshwar.reelplay.editor.EditorActivity
import com.eshwar.reelplay.player.PlaybackPrefs
import com.eshwar.reelplay.player.PlayerActivity
import com.eshwar.reelplay.settings.SettingsActivity
import com.eshwar.reelplay.update.Updates
import com.eshwar.reelplay.report.ReportDialog
import com.eshwar.reelplay.report.Reports
import kotlinx.coroutines.launch
import com.eshwar.reelplay.ui.formatDuration
import com.eshwar.reelplay.ui.canRead
import com.eshwar.reelplay.ui.formatSize
import com.eshwar.reelplay.ui.startActivitySafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun mediaPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/** Full access, or Android 14's "selected videos only", both of which let the library show something. */
private fun hasMediaAccess(context: Context): Boolean =
    mediaPermissions().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

/**
 * The Media Player tab: folders (or every video), search, sort & filter. [insets] are left to
 * this screen's own bars; the home screen's bottom navigation takes care of the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(insets: WindowInsets = WindowInsets(0, 0, 0, 0)) {
    val context = LocalContext.current
    val prefs = remember { PlaybackPrefs(context) }

    var hasAccess by remember { mutableStateOf(hasMediaAccess(context)) }
    var videos by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    // false: folders (the default, like MX Player); true: every video in one list.
    var allVideos by rememberSaveable { mutableStateOf(false) }
    val viewPrefs = remember { LibraryViewPrefs(context) }
    var sort by remember { mutableStateOf(viewPrefs.sort) }
    var filter by remember { mutableStateOf(viewPrefs.filter) }
    var openFolderId by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showStreamDialog by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    val updateScope = rememberCoroutineScope()
    var infoFor by remember { mutableStateOf<VideoItem?>(null) }
    // Resume positions change while the player is open; bump this to redraw progress strips.
    var progressTick by remember { mutableIntStateOf(0) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { hasAccess = hasMediaAccess(context) }

    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { reloadTick++ }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasAccess = hasMediaAccess(context)
        progressTick++
        reloadTick++
    }

    LaunchedEffect(hasAccess, reloadTick) {
        if (!hasAccess) return@LaunchedEffect
        loading = videos.isEmpty()
        videos = VideoRepository.loadVideos(context)
        loading = false
    }

    val folders = remember(videos) { VideoRepository.groupIntoFolders(videos) }
    val openFolder = folders.firstOrNull { it.id == openFolderId }

    BackHandler(enabled = searching || openFolderId != null) {
        if (searching) {
            searching = false
            query = ""
        } else {
            openFolderId = null
        }
    }

    fun play(list: List<VideoItem>, index: Int) {
        context.startActivitySafely(
            PlayerActivity.intent(context, list.map { it.uri }, list.map { it.name }, index),
        )
    }

    fun delete(video: VideoItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val sender = MediaStore.createDeleteRequest(context.contentResolver, listOf(video.uri)).intentSender
            deleteLauncher.launch(IntentSenderRequest.Builder(sender).build())
        }
    }

    val actions = VideoActions(
        play = ::play,
        edit = { context.startActivitySafely(EditorActivity.intent(context, listOf(it.uri))) },
        share = { shareVideo(context, it.uri) },
        info = { infoFor = it },
        delete = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ::delete else null,
    )

    Scaffold(
        contentWindowInsets = insets,
        topBar = {
            if (searching) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search")
                        }
                    },
                    title = {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search videos") },
                            singleLine = true,
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    },
                    actions = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") }
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        if (openFolder != null) {
                            IconButton(onClick = { openFolderId = null }) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                            }
                        }
                    },
                    title = {
                        Text(
                            openFolder?.name ?: if (allVideos) "All videos" else "Folders",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    actions = {
                        if (openFolder == null) {
                            IconButton(onClick = { allVideos = !allVideos }) {
                                Icon(
                                    if (allVideos) Icons.Rounded.Folder else Icons.Rounded.VideoLibrary,
                                    if (allVideos) "Show folders" else "Show all videos",
                                )
                            }
                        }
                        IconButton(onClick = { searching = true }) { Icon(Icons.Rounded.Search, "Search") }
                        Box {
                            IconButton(onClick = { showSortMenu = true }) {
                                Icon(Icons.AutoMirrored.Rounded.Sort, "Sort")
                            }
                        }
                        Box {
                            IconButton(onClick = { showMoreMenu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                            DropdownMenu(showMoreMenu, onDismissRequest = { showMoreMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Network stream") },
                                    leadingIcon = { Icon(Icons.Rounded.Language, null) },
                                    onClick = { showMoreMenu = false; showStreamDialog = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Report a problem") },
                                    leadingIcon = { Icon(Icons.Rounded.BugReport, null) },
                                    onClick = { showMoreMenu = false; showReport = true },
                                )
                                DropdownMenuItem(
                                    text = { Text("Check for updates") },
                                    leadingIcon = { Icon(Icons.Rounded.SystemUpdate, null) },
                                    onClick = {
                                        showMoreMenu = false
                                        updateScope.launch { Updates.check(userAsked = true) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Settings") },
                                    leadingIcon = { Icon(Icons.Rounded.Settings, null) },
                                    onClick = {
                                        showMoreMenu = false
                                        context.startActivitySafely(SettingsActivity.intent(context))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Refresh") },
                                    leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                    onClick = { showMoreMenu = false; reloadTick++ },
                                )
                            }
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { context.startActivitySafely(EditorActivity.intent(context, emptyList())) },
                icon = { Icon(Icons.Rounded.ContentCut, null) },
                text = { Text("New edit") },
                containerColor = MaterialTheme.colorScheme.secondary,
                contentColor = MaterialTheme.colorScheme.onSecondary,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!hasAccess) {
                PermissionPrompt { permissionLauncher.launch(mediaPermissions()) }
                return@Column
            }

            // Read once per refresh; the player updates these while it's open.
            val playedTimes = remember(progressTick) { prefs.playedTimes() }
            val resumes = remember(progressTick) { prefs.resumePositions() }
            val playedAt: (VideoItem) -> Long = { playedTimes[it.uri.toString()] ?: 0L }
            val newSince = remember { viewPrefs.newSinceSec }
            val showNew = Prefs.version.collectAsState().value.let { Prefs.showNewTags }
            val isNew: (VideoItem) -> Boolean = { showNew && it.isNew(playedAt(it), newSince) }
            fun arrange(list: List<VideoItem>): List<VideoItem> {
                val now = System.currentTimeMillis()
                return sort.sort(
                    list.filter {
                        (query.isBlank() || it.name.contains(query, ignoreCase = true)) &&
                            filter.matches(it, playedAt(it), resumes[it.uri.toString()] ?: 0L, now, newSince)
                    },
                    playedAt,
                )
            }
            val filtered = remember(videos, query, sort, filter, playedTimes) { arrange(videos) }
            val shownFolders = remember(folders, sort, filter, playedTimes) {
                sort.sortFolders(
                    folders.mapNotNull { f -> arrange(f.videos).takeIf { it.isNotEmpty() }?.let { f.copy(videos = it) } },
                    playedAt,
                )
            }

            if (filter != PlayFilter.ALL || sort != LibrarySort()) {
                SortSummary(sort, filter, onClick = { showSortMenu = true })
            }

            when {
                searching -> VideoList(filtered, prefs, playedTimes, isNew, progressTick, actions)
                openFolder != null -> VideoList(arrange(openFolder.videos), prefs, playedTimes, isNew, progressTick, actions)
                else -> {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (!loading && videos.isEmpty()) {
                        EmptyState()
                    } else if (!allVideos) {
                        FolderList(shownFolders, newCount = { f -> f.videos.count(isNew) }) { openFolderId = it.id }
                    } else {
                        VideoList(filtered, prefs, playedTimes, isNew, progressTick, actions)
                    }
                }
            }
        }
    }

    if (showSortMenu) {
        SortDialog(
            sort, filter,
            onDismiss = { showSortMenu = false },
            onApply = { newSort, newFilter ->
                sort = newSort
                filter = newFilter
                viewPrefs.sort = newSort
                viewPrefs.filter = newFilter
                showSortMenu = false
            },
        )
    }

    if (showReport) ReportDialog(Reports.Kind.PROBLEM, onDismiss = { showReport = false })

    if (showStreamDialog) {
        StreamDialog(
            onDismiss = { showStreamDialog = false },
            onPlay = { url ->
                showStreamDialog = false
                context.startActivitySafely(PlayerActivity.intent(context, listOf(url.toUri()), listOf(url), 0))
            },
        )
    }

    infoFor?.let { video ->
        AlertDialog(
            onDismissRequest = { infoFor = null },
            confirmButton = { TextButton(onClick = { infoFor = null }) { Text("OK") } },
            title = { Text("Properties") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoRow("Name", video.name)
                    InfoRow("Folder", video.folderName)
                    InfoRow("Length", formatDuration(video.durationMs))
                    InfoRow("Size", formatSize(video.sizeBytes))
                    if (video.width > 0) InfoRow("Resolution", "${video.width} × ${video.height}")
                    InfoRow(
                        "Added",
                        java.text.DateFormat.getDateTimeInstance().format(java.util.Date(video.dateAddedSec * 1000)),
                    )
                    val playedAt = remember(video) { prefs.playedTimes()[video.uri.toString()] ?: 0L }
                    InfoRow(
                        "Last played",
                        if (playedAt > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(playedAt))
                        else "Never",
                    )
                }
            },
        )
    }
}

private class VideoActions(
    val play: (List<VideoItem>, Int) -> Unit,
    val edit: (VideoItem) -> Unit,
    val share: (VideoItem) -> Unit,
    val info: (VideoItem) -> Unit,
    val delete: ((VideoItem) -> Unit)?,
)

private fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/*"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivitySafely(Intent.createChooser(send, "Share video"), "Couldn't share")
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, Modifier.width(92.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
private fun PermissionPrompt(onGrant: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Rounded.VideoLibrary, null,
            Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text("Let All Media Player find your videos", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Videos stay on your phone. The library only reads what's already there.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrant) { Text("Allow access") }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("No videos yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Record something, or tap New edit to start from photos.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FolderList(folders: List<VideoFolder>, newCount: (VideoFolder) -> Int, onOpen: (VideoFolder) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(top = 4.dp, bottom = 96.dp)) {
        items(folders, key = { it.id }) { folder ->
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(folder) }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FolderTile(folder.name, newCount(folder))
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(folder.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${folder.videos.size} video${if (folder.videos.size == 1) "" else "s"}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * MX-style folder: a big grey folder with a hint of what's inside (camera, downloads, chat
 * apps), and a red bubble counting new videos.
 */
@Composable
private fun FolderTile(name: String, newCount: Int) {
    Box(Modifier.size(width = 76.dp, height = 60.dp)) {
        Icon(
            Icons.Rounded.Folder, null,
            tint = Color(0xFF3E444D),
            modifier = Modifier.fillMaxSize(),
        )
        folderHint(name)?.let { hint ->
            Icon(
                hint, null,
                tint = Color(0xFF6B727C),
                modifier = Modifier.align(Alignment.Center).padding(top = 6.dp).size(24.dp),
            )
        }
        if (newCount > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 2.dp).size(22.dp).background(NEW_RED, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (newCount > 99) "99+" else newCount.toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

private fun folderHint(name: String) = name.lowercase().let { n ->
    when {
        "camera" in n || n == "dcim" -> Icons.Rounded.PhotoCamera
        "whatsapp" in n || "telegram" in n || "messenger" in n || "signal" in n -> Icons.AutoMirrored.Rounded.Chat
        "download" in n || "reelplay" in n -> Icons.Rounded.Download
        "screen" in n && "record" in n -> Icons.Rounded.Videocam
        "movie" in n || "film" in n -> Icons.Rounded.Movie
        else -> null
    }
}

@Composable
private fun VideoList(
    videos: List<VideoItem>,
    prefs: PlaybackPrefs,
    playedTimes: Map<String, Long>,
    isNew: (VideoItem) -> Boolean,
    progressTick: Int,
    actions: VideoActions,
) {
    if (videos.isEmpty()) {
        Text(
            "No videos match this filter.",
            Modifier.fillMaxWidth().padding(32.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
        items(videos.size, key = { videos[it].id }) { index ->
            val video = videos[index]
            val progress = remember(video.uri, progressTick) { prefs.progress(video.uri, video.durationMs) }
            val playedAt = playedTimes[video.uri.toString()] ?: 0L
            VideoRow(video, progress, playedAt, isNew(video), onClick = { actions.play(videos, index) }, actions = actions)
        }
    }
}

@Composable
private fun VideoRow(video: VideoItem, progress: Float, playedAt: Long, isNew: Boolean, onClick: () -> Unit, actions: VideoActions) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(128.dp).height(72.dp).clip(RoundedCornerShape(8.dp))) {
            if (Prefs.showThumbnails) {
                VideoThumbnail(video.uri, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (isNew) NewBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
            Text(
                formatDuration(video.durationMs),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(4.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            if (progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66000000))) {
                    Box(
                        Modifier.fillMaxWidth(progress).height(3.dp)
                            .background(MaterialTheme.colorScheme.secondary),
                    )
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(video.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(2.dp))
            val res = if (video.height > 0) " · ${minOf(video.width, video.height)}p" else ""
            Text(
                formatSize(video.sizeBytes) + res,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Added ${formatDay(video.dateAddedSec * 1000)}" +
                    if (playedAt > 0) " · Played ${formatWhen(playedAt)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Options") }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play") }, leadingIcon = { Icon(Icons.Rounded.PlayArrow, null) },
                    onClick = { menu = false; onClick() },
                )
                DropdownMenuItem(
                    text = { Text("Edit") }, leadingIcon = { Icon(Icons.Rounded.ContentCut, null) },
                    onClick = { menu = false; actions.edit(video) },
                )
                DropdownMenuItem(
                    text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) },
                    onClick = { menu = false; actions.share(video) },
                )
                DropdownMenuItem(
                    text = { Text("Properties") }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                    onClick = { menu = false; actions.info(video) },
                )
                actions.delete?.let { delete ->
                    DropdownMenuItem(
                        text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) },
                        onClick = { menu = false; delete(video) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamDialog(onDismiss: () -> Unit, onPlay: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    val valid = url.startsWith("http://") || url.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Network stream") },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                label = { Text("Video URL") },
                placeholder = { Text("https://…/video.mp4") },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onPlay(url) }, enabled = valid) { Text("Play") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
