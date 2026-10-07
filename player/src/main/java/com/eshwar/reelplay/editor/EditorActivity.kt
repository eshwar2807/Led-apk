package com.eshwar.reelplay.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.core.content.IntentCompat
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import com.eshwar.reelplay.ui.ReelPlayTheme

/** The CapCut-style editor: multi-clip timeline, live preview, export. */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class EditorActivity : ComponentActivity() {

    private lateinit var player: CompositionPlayer
    private lateinit var exporter: Exporter
    private val state = EditorState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        player = CompositionPlayer.Builder(this).build()
        exporter = Exporter(this)
        val initial = incomingUris(intent)

        setContent {
            ReelPlayTheme {
                EditorScreen(
                    state = state,
                    player = player,
                    exporter = exporter,
                    initialUris = initial,
                    onClose = ::finish,
                )
            }
        }
    }

    private fun incomingUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java).orEmpty()
    }

    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        exporter.cancel()
        player.release()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URIS = "uris"

        fun intent(context: Context, uris: List<Uri>): Intent =
            Intent(context, EditorActivity::class.java).apply {
                // No URI grant needed for our own activity (see PlayerActivity.intent).
                putParcelableArrayListExtra(EXTRA_URIS, ArrayList(uris))
            }
    }
}
