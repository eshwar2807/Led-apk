package com.eshwar.reelplay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eshwar.reelplay.library.LibraryScreen
import com.eshwar.reelplay.ui.ReelPlayTheme

/** The video library: folders, all videos, and the way into the editor. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            ReelPlayTheme {
                LibraryScreen()
            }
        }
    }
}
