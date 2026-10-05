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
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import com.eshwar.reelplay.settings.Orientation
import com.eshwar.reelplay.settings.Prefs
import com.eshwar.reelplay.settings.ResumeMode
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
import androidx.media3.exoplayer.DefaultRenderersFactory
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
    private val codecSelector = RecoveringCodecSelector()
    private lateinit var torrentLoad: TorrentLoadControl

    /** Points the torrent buffering rule at the torrent now playing, once its length is known. */
    private fun updateTorrentTarget() {
        val stream = player.currentMediaItem?.localConfiguration?.uri?.let(TorrentEngine::stream)
        val duration = player.duration
        torrentLoad.target = if (stream != null && duration > 0) TorrentLoadControl.Target(stream, duration) else null
    }

    override fun torrentBufferPlan(): com.eshwar.reelplay.torrent.StreamReadiness.Plan? = torrentLoad.lastPlan
    private lateinit var recovery: DecoderRecovery

    /**
     * Platform decoders first; FFmpeg (DTS, TrueHD, Dolby Digital and more) for audio the phone
     * can't decode itself; and a fallback to the next decoder when one fails to start.
     */
    private fun renderers() = DefaultRenderersFactory(this)
        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        .setEnableDecoderFallback(true)
        .setMediaCodecSelector(codecSelector)

    override fun recoverFrom(error: androidx.media3.common.PlaybackException): String? = recovery.recover(error)

    override fun resetRecovery() = recovery.reset()
    private lateinit var prefs: PlaybackPrefs
    private var enhancer: LoudnessEnhancer? = null
    private var orientationChosenByUser = false

    private val inPip = mutableStateOf(false)

    /** The video waiting on Android's delete confirmation. */
    private var deleting: Uri? = null

    private val deleteConfirm = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val uri = deleting ?: return@registerForActivityResult
        deleting = null
        if (result.resultCode == RESULT_OK) removeDeleted(uri)
    }

    /** Only files in the phone's media library can be deleted from here (Android 11+). */
    override fun canDeleteCurrent(): Boolean {
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return false
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uri.scheme == "content" &&
            uri.authority == android.provider.MediaStore.AUTHORITY
    }

    override fun deleteCurrent() {
        if (!canDeleteCurrent() || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val uri = player.currentMediaItem?.localConfiguration?.uri ?: return
        player.pause()
        deleting = uri
        try {
            // Android asks the user to confirm, then deletes it.
            val sender = android.provider.MediaStore.createDeleteRequest(contentResolver, listOf(uri)).intentSender
            deleteConfirm.launch(androidx.activity.result.IntentSenderRequest.Builder(sender).build())
        } catch (e: Exception) {
            deleting = null
            Toast.makeText(this, "Couldn't delete: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    /** Takes a deleted video out of the queue and plays on, or closes if it was the only one. */
    private fun removeDeleted(uri: Uri) {
        prefs.forget(uri)
        val index = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).localConfiguration?.uri == uri }
        Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show()
        if (index == null) return
        if (player.mediaItemCount <= 1) {
            finish()
            return
        }
        player.removeMediaItem(index)
        player.prepare()
        player.play()
    }

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

        player = ExoPlayer.Builder(this, renderers())
            // torrent:// items read straight from the partly downloaded file.
            .setMediaSourceFactory(DefaultMediaSourceFactory(TorrentDataSource.Factory(this)))
            // After a stall (a slow torrent or network), gather 10 s before resuming rather than
            // the default 5 s, so playback doesn't stutter stop-start-stop.
            .setLoadControl(TorrentLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS,
                        DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                        10_000,
                    )
                    .build(),
            ).also { torrentLoad = it })
            .setSeekBackIncrementMs(Prefs.doubleTapSeekSec * 1000L)
            .setSeekForwardIncrementMs(Prefs.doubleTapSeekSec * 1000L)
            .setHandleAudioBecomingNoisy(true)
            .build()
        recovery = DecoderRecovery(player, codecSelector)
        player.setPlaybackSpeed(if (Prefs.rememberSpeed) prefs.playbackSpeed else 1f)
        applyOrientationSetting()
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
                resumeAsk.value?.let { at ->
                    ResumeDialog(
                        at,
                        onResume = { answerResume(fromStart = false) },
                        onStartOver = { answerResume(fromStart = true) },
                    )
                }
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
        val saved = prefs.position(uris[index])
        val mode = Prefs.resumeMode
        val resume = if (mode == ResumeMode.START_OVER) 0L else saved
        try {
            player.setMediaItems(items, index, resume)
            player.prepare()
            if (resume > 0 && mode == ResumeMode.ASK) askToResume(resume) else player.play()
        } catch (e: RuntimeException) {
            // A link the player has no support for: say so rather than crash.
            Log.w(TAG, "Can't play ${uris[index]}", e)
            Toast.makeText(this, "All Media Player can't play this link: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (resume > 0 && mode == ResumeMode.RESUME) {
            Toast.makeText(this, "Resumed from ${formatDuration(resume)}", Toast.LENGTH_SHORT).show()
        }
    }

    /** Settings → Resume playback set to "Ask": hold at the saved spot and let the user pick. */
    private val resumeAsk = mutableStateOf<Long?>(null)

    private fun askToResume(at: Long) {
        player.pause()
        resumeAsk.value = at
    }

    private fun answerResume(fromStart: Boolean) {
        resumeAsk.value = null
        if (fromStart) player.seekTo(0)
        player.play()
    }

    private fun applyOrientationSetting() {
        when (Prefs.orientation) {
            Orientation.AUTO -> return // Chosen per video, from its shape.
            Orientation.LANDSCAPE -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            Orientation.PORTRAIT -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            Orientation.SYSTEM -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        orientationChosenByUser = true
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
        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = updateTorrentTarget()

        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            updateTorrentTarget()
            // Each video gets its own recovery attempts (decoders found broken stay skipped).
            recovery.reset()
        }

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
            if (saved <= 0) return
            // The seek lands in the same item, so it doesn't come back through here.
            when (Prefs.resumeMode) {
                ResumeMode.START_OVER -> Unit
                ResumeMode.RESUME -> player.seekTo(newPosition.mediaItemIndex, saved)
                ResumeMode.ASK -> {
                    player.seekTo(newPosition.mediaItemIndex, saved)
                    askToResume(saved)
                }
            }
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

    override val canBoost: Boolean get() = enhancer != null && Prefs.volumeBoost

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
        if (Prefs.rememberSpeed) prefs.playbackSpeed = speed
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
        private const val TAG = "PlayerActivity"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_TITLES = "titles"
        private const val EXTRA_INDEX = "index"

        fun intent(context: Context, uris: List<Uri>, titles: List<String>, index: Int): Intent =
            Intent(context, PlayerActivity::class.java).apply {
                data = uris.getOrNull(index)
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
                putStringArrayListExtra(EXTRA_TITLES, ArrayList(titles))
                putExtra(EXTRA_INDEX, index)
                // No FLAG_GRANT_READ_URI_PERMISSION: this is our own activity, which reads with
                // the app's own access. Asking Android to grant a URI we can't read ourselves
                // (e.g. under "Allow limited access") made startActivity throw.
            }
    }
}

/** What the player UI may ask of the window and system around it. */
/** "Resume from 12:34" or "Start over", for Settings → Resume playback → Ask every time. */
@androidx.compose.runtime.Composable
private fun ResumeDialog(at: Long, onResume: () -> Unit, onStartOver: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onResume,
        title = { androidx.compose.material3.Text("Continue watching?") },
        text = { androidx.compose.material3.Text("You stopped this video at ${formatDuration(at)}.") },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onResume) {
                androidx.compose.material3.Text("Resume from ${formatDuration(at)}")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onStartOver) { androidx.compose.material3.Text("Start over") }
        },
    )
}

interface PlayerHost {
    fun canDeleteCurrent(): Boolean
    /** Deletes the playing video (after Android's confirmation) and moves on. */
    fun deleteCurrent()
    /** What a buffering torrent is waiting for, or null. */
    fun torrentBufferPlan(): com.eshwar.reelplay.torrent.StreamReadiness.Plan?
    /** Tries to get past a decoder error; returns what it did, or null if it couldn't. */
    fun recoverFrom(error: androidx.media3.common.PlaybackException): String?
    fun resetRecovery()
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
