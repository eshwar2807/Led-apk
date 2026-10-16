package com.eshwar.reelplay.faces

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.eshwar.reelplay.library.VideoThumbnail
import com.eshwar.reelplay.player.PlayerActivity
import com.eshwar.reelplay.settings.Prefs
import com.eshwar.reelplay.ui.ReelPlayTheme
import com.eshwar.reelplay.ui.formatDuration
import com.eshwar.reelplay.ui.startActivitySafely
import kotlinx.coroutines.launch

/** People: everyone found in the phone's photos and videos, and what each of them is in. */
class PeopleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ReelPlayTheme { PeopleScreen(onClose = ::finish) } }
    }

    companion object {
        fun intent(context: Context) = Intent(context, PeopleActivity::class.java)
    }
}

private fun photoPermissions(): Array<String> = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
        Manifest.permission.READ_MEDIA_IMAGES,
        Manifest.permission.READ_MEDIA_VIDEO,
        Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
    )
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

private fun canReadPhotos(context: Context): Boolean {
    val granted = { p: String -> ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED }
    return when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            granted(Manifest.permission.READ_MEDIA_IMAGES) || granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> granted(Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}

private enum class Kind(val label: String) { ALL("All"), PHOTOS("Photos"), VIDEOS("Videos") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PeopleScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefsVersion by Prefs.version.collectAsState()
    val enabled = remember(prefsVersion) { Prefs.faceGrouping }
    var photoAccess by remember { mutableStateOf(canReadPhotos(context)) }
    val scan by FaceIndex.state.collectAsState()
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var kind by rememberSaveable { mutableStateOf(Kind.ALL) }
    var renaming by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        photoAccess = canReadPhotos(context)
        if (Prefs.faceGrouping) FaceIndex.scanInBackground(context)
    }

    // Search right here while the screen is open: background work can be held back for a long
    // time by battery savers (some phones barely run it at all). The worker carries on after.
    LaunchedEffect(enabled, photoAccess) {
        if (enabled) {
            FaceIndex.scanInBackground(context)
            FaceIndex.scan(context)
        }
    }
    // Refresh the groups as the scan goes: often at first, so people show up quickly.
    val step = if (scan.done < 50) 10 else 50
    var stats by remember { mutableStateOf(IndexStats()) }
    LaunchedEffect(enabled, reload, scan.running, scan.done / step) {
        people = if (enabled) runCatching { FaceIndex.people(context) }.getOrNull() ?: people else null
        stats = FaceIndex.stats(context)
    }

    // A person is found again by any of their faces; the cover face can change as more are found.
    val open = openId?.let { id -> people?.firstOrNull { it.coverFaceId == id } }
    BackHandler(enabled = openId != null) { openId = null }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { if (openId != null) openId = null else onClose() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (open != null) open.name ?: "Add a name" else "People",
                            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (open != null) {
                            IconButton(onClick = { renaming = true }) { Icon(Icons.Rounded.Edit, "Name this person") }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                !enabled -> Intro(onEnable = {
                    Prefs.faceGrouping = true
                    if (!canReadPhotos(context)) permissionLauncher.launch(photoPermissions())
                    FaceIndex.scanInBackground(context)
                })
                else -> {
                    if (!photoAccess) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Only videos are searched. Allow photos to find people in them too.",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { permissionLauncher.launch(photoPermissions()) }) { Text("Allow") }
                        }
                    }
                    if (scan.running) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                if (scan.total == 0) "Getting your photos and videos ready…"
                                else "Looking for faces… ${scan.done} of ${scan.total}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            if (scan.total == 0) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                            } else {
                                LinearProgressIndicator(
                                    progress = { scan.done.toFloat() / scan.total },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    scan.error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (open == null && (stats.photos + stats.videos) > 0) {
                        Text(
                            "Looked at ${plural(stats.photos, "photo")} and ${plural(stats.videos, "video")} · " +
                                "${plural(stats.faces, "face")} found",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    if (open != null) {
                        PersonMedia(open, kind, onKind = { kind = it })
                    } else {
                        PeopleGrid(people, scan.running, onOpen = { openId = it.coverFaceId; kind = Kind.ALL })
                    }
                }
            }
        }
    }

    if (renaming && open != null) {
        var name by remember { mutableStateOf(open.name.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Who is this?") },
            text = {
                OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Name") })
            },
            confirmButton = {
                TextButton(onClick = {
                    renaming = false
                    scope.launch { FaceIndex.rename(context, open, name); reload++ }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Intro(onEnable: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.Face, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Find people in your photos and videos", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "The app looks for faces in your photos and videos and groups the same face together, so you can " +
                "see everything someone is in. It all happens on this phone: no photo, video or face data is " +
                "uploaded anywhere. You can turn it off in Settings, which deletes the face data.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onEnable) { Text("Turn on") }
    }
}

@Composable
private fun PeopleGrid(people: List<Person>?, scanning: Boolean, onOpen: (Person) -> Unit) {
    if (people.isNullOrEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                when {
                    people == null || scanning -> "People appear here as they're found."
                    else -> "Nobody found yet. Someone shows up once their face is in at least two photos or videos."
                },
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(104.dp),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(people, key = { it.coverFaceId }) { p ->
            Column(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(p) }.padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                FaceAvatar(p.coverFaceId, Modifier.fillMaxWidth().aspectRatio(1f))
                Spacer(Modifier.height(6.dp))
                Text(
                    p.name ?: "Unnamed", fontWeight = if (p.name != null) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(counts(p), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"

private fun counts(p: Person): String = listOfNotNull(
    p.photos.takeIf { it > 0 }?.let { if (it == 1) "1 photo" else "$it photos" },
    p.videos.takeIf { it > 0 }?.let { if (it == 1) "1 video" else "$it videos" },
).joinToString(" · ")

@Composable
private fun FaceAvatar(faceId: Long, modifier: Modifier) {
    val context = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, faceId) { value = FaceIndex.faceCrop(context, faceId) }
    Box(modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Rounded.Face, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Everything one person is in, filtered to photos or videos. */
@Composable
private fun PersonMedia(person: Person, kind: Kind, onKind: (Kind) -> Unit) {
    val context = LocalContext.current
    val shown = when (kind) {
        Kind.ALL -> person.items
        Kind.PHOTOS -> person.items.filter { !it.isVideo }
        Kind.VIDEOS -> person.items.filter { it.isVideo }
    }
    Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (k in Kind.entries) {
            val n = when (k) { Kind.ALL -> person.items.size; Kind.PHOTOS -> person.photos; Kind.VIDEOS -> person.videos }
            FilterChip(selected = k == kind, onClick = { onKind(k) }, label = { Text("${k.label} ($n)") })
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(110.dp),
        contentPadding = PaddingValues(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(shown, key = { it.key }) { item ->
            Box(
                Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable {
                    if (item.isVideo) {
                        // Play this video, with their other videos next.
                        val videos = person.items.filter { it.isVideo }
                        context.startActivitySafely(
                            PlayerActivity.intent(context, videos.map { it.uri }, videos.map { it.name }, videos.indexOf(item)),
                        )
                    } else {
                        context.startActivitySafely(
                            Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, "image/*")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            "Couldn't show the photo",
                        )
                    }
                },
            ) {
                VideoThumbnail(item.uri, Modifier.fillMaxSize())
                if (item.isVideo) {
                    Row(
                        Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.PlayArrow, null, Modifier.size(12.dp), tint = Color.White)
                        Text(formatDuration(item.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
