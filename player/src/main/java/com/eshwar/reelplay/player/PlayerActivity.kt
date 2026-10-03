package com.eshwar.reelplay.player

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Rational
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.eshwar.reelplay.editor.EditorActivity
import com.eshwar.reelplay.torrent.TorrentDataSource
import com.eshwar.reelplay.torrent.TorrentEngine
import com.eshwar.reelplay.ui.ReelPlayTheme
import com.eshwar.reelplay.ui.formatDuration

/**
 * Full-screen playback. Owns the [ExoPlayer], the system-level bits (brightness, orientation,
 * picture-in-picture, loudness boost) and resume bookkeeping; [PlayerScreen] draws everything.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerHost {

    private lateinit var player: ExoPlayer
    private lateinit var prefs: PlaybackPrefs
    private var enhancer: LoudnessEnhancer? = null
    private var orientationChosenByUser = false

    private val inPip = mutableStateOf(false)

    private val subtitlePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) attachSubtitle(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = PlaybackPrefs(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        hideSystemBars()

        player = ExoPlayer.Builder(this)
            // torrent:// items read straight from the partly downloaded file.
            .setMediaSourceFactory(DefaultMediaSourceFactory(TorrentDataSource.Factory(this)))
            // After a stall (a slow torrent or network), gather 10 s before resuming rather than
            // the default 5 s, so playback doesn't stutter stop-start-stop.
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        10_000,
                    )
                    .build(),
            )
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.setPlaybackSpeed(prefs.playbackSpeed)
        player.addListener(listener)
        // ExoPlayer assigns its own session ID on a background thread; pin one up front so the
        // loudness boost attaches to this player rather than the global mix.
        val sessionId = (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        player.setAudioSessionId(sessionId)
        enhancer = try {
            LoudnessEnhancer(sessionId).apply { enabled = true }
        } catch (_: Exception) {
            null // Some devices refuse audio effects on a session; the slider just stops at 100%.
        }

        load(intent)

        setContent {
            ReelPlayTheme {
                PlayerScreen(player = player, host = this, inPip = inPip.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        saveCurrent()
        val previous = queuedUris()
        load(intent)
        closeTorrents(previous - queuedUris().toSet())
    }

    private fun queuedUris(): List<Uri> =
        (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).localConfiguration?.uri }

    /** Torrents exist only to feed this player; once it lets go, stop them and drop their data. */
    private fun closeTorrents(uris: Collection<Uri>) {
        uris.filter { it.scheme == TorrentEngine.SCHEME }.forEach(TorrentEngine::close)
    }

    private fun load(intent: Intent) {
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java)
            ?: listOfNotNull(intent.data)
        if (uris.isEmpty()) {
            Toast.makeText(this, "Nothing to play", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val titles = intent.getStringArrayListExtra(EXTRA_TITLES)
        val index = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, uris.lastIndex)
        val items = uris.mapIndexed { i, uri ->
            MediaItem.Builder()
                .setUri(uri)
                .setMediaId(uri.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(titles?.getOrNull(i) ?: displayName(uri)).build(),
                )
                .build()
        }
        val resume = prefs.position(uris[index])
        player.setMediaItems(items, index, resume)
        player.prepare()
        player.play()
        if (resume > 0) {
            Toast.makeText(this, "Resumed from ${formatDuration(resume)}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0)?.let { return it }
                }
            } catch (_: Exception) {
            }
        }
        return uri.lastPathSegment ?: "Video"
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePipParams()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
            val old = oldPosition.mediaItem ?: return
            val oldUri = old.localConfiguration?.uri ?: return
            val title = old.mediaMetadata.title?.toString() ?: "Video"
            if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                prefs.save(oldUri, 0, 0, title) // Watched to the end: start fresh next time.
            } else {
                prefs.save(oldUri, oldPosition.positionMs, C.TIME_UNSET, title)
            }
            val newUri = newPosition.mediaItem?.localConfiguration?.uri ?: return
            val saved = prefs.position(newUri)
            // The seek lands in the same item, so it doesn't come back through here.
            if (saved > 0) player.seekTo(newPosition.mediaItemIndex, saved)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width == 0 || videoSize.height == 0) return
            if (!orientationChosenByUser && !inPip.value) {
                // Like MX: landscape videos open landscape, tall ones stay upright.
                val w = videoSize.width * videoSize.pixelWidthHeightRatio
                requestedOrientation = if (w >= videoSize.height) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                } else {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                }
            }
            updatePipParams()
        }
    }

    private fun saveCurrent() {
        val item = player.currentMediaItem ?: return
        val uri = item.localConfiguration?.uri ?: return
        prefs.save(uri, player.currentPosition, player.duration, item.mediaMetadata.title?.toString() ?: "Video")
    }

    // ---- PlayerHost ----

    override fun close() = finish()

    override fun setBrightness(level: Float) {
        window.attributes = window.attributes.apply { screenBrightness = level.coerceIn(0.01f, 1f) }
    }

    override fun currentBrightness(): Float {
        val own = window.attributes.screenBrightness
        if (own >= 0) return own
        return try {
            android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (_: Exception) {
            0.5f
        }
    }

    override val canBoost: Boolean get() = enhancer != null

    override fun setBoost(fraction: Float) {
        // Up to +12 dB on top of full system volume, the same headroom MX offers at 200%.
        enhancer?.let {
            try {
                it.setTargetGain((fraction.coerceIn(0f, 1f) * 1200).toInt())
            } catch (_: Exception) {
            }
        }
    }

    override fun cycleOrientation(): String {
        orientationChosenByUser = true
        val (next, label) = when (requestedOrientation) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT to "Portrait"
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR to "Auto-rotate"
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE to "Landscape"
        }
        requestedOrientation = next
        return label
    }

    override fun enterPip() {
        try {
            enterPictureInPictureMode(pipParams())
        } catch (_: IllegalStateException) {
            Toast.makeText(this, "Picture-in-picture is off for this app", Toast.LENGTH_SHORT).show()
        }
    }

    override fun pickSubtitle() {
        subtitlePicker.launch(arrayOf("application/x-subrip", "text/vtt", "text/*", "application/octet-stream"))
    }

    override fun openInEditor() {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        if (uri.scheme == TorrentEngine.SCHEME || uri.scheme == "http" || uri.scheme == "https") {
            Toast.makeText(this, "Only videos on this phone can be edited", Toast.LENGTH_SHORT).show()
            return
        }
        player.pause()
        startActivity(EditorActivity.intent(this, listOf(uri)))
    }

    override fun rememberSpeed(speed: Float) {
        prefs.playbackSpeed = speed
    }

    private fun attachSubtitle(uri: Uri) {
        val name = displayName(uri).lowercase()
        val mime = when {
            name.endsWith(".vtt") -> MimeTypes.TEXT_VTT
            name.endsWith(".ass") || name.endsWith(".ssa") -> MimeTypes.TEXT_SSA
            name.endsWith(".ttml") || name.endsWith(".xml") -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        val current = player.currentMediaItem ?: return
        val subtitle = MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLabel(displayName(uri))
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val updated = current.buildUpon().setSubtitleConfigurations(listOf(subtitle)).build()
        val position = player.currentPosition
        val index = player.currentMediaItemIndex
        player.replaceMediaItem(index, updated)
        player.seekTo(index, position)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        Toast.makeText(this, "Subtitles loaded", Toast.LENGTH_SHORT).show()
    }

    // ---- Picture-in-picture ----

    private fun pipParams(): PictureInPictureParams {
        val size = player.videoSize
        val builder = PictureInPictureParams.Builder()
        if (size.width > 0 && size.height > 0) {
            // The system rejects anything outside roughly 1:2.39 .. 2.39:1.
            val ratio = (size.width.toFloat() / size.height).coerceIn(0.42f, 2.39f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(player.isPlaying)
            builder.setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        try {
            setPictureInPictureParams(pipParams())
        } catch (_: Exception) {
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Android 12+ enters automatically via setAutoEnterEnabled.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && player.isPlaying) {
            enterPip()
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) hideSystemBars()
    }

    // ---- Lifecycle ----

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        saveCurrent()
    }

    override fun onStop() {
        super.onStop()
        // Leaving PiP by swiping it away lands here too: stop rather than play into the void.
        player.pause()
        saveCurrent()
    }

    override fun onDestroy() {
        saveCurrent()
        if (isFinishing) closeTorrents(queuedUris())
        player.removeListener(listener)
        player.release()
        enhancer?.release()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    companion object {
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"

        fun intent(context: Context, uris: List<Uri>, titles: List<String>, index: Int): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                data = uris.getOrNull(index)
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                putExtra(EXTRA_INDEX, index)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
}

/** What the player UI may ask of the window and system around it. */
interface PlayerHost {
    fun close()
    fun setBrightness(level: Float)
    fun currentBrightness(): Float
    val canBoost: Boolean
    fun setBoost(fraction: Float)
    fun cycleOrientation(): String
    fun enterPip()
    fun pickSubtitle()
    fun openInEditor()
    fun rememberSpeed(speed: Float)
}
