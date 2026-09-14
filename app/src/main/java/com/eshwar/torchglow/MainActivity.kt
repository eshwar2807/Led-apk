package com.eshwar.torchglow

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.eshwar.torchglow.torch.TorchController
import com.eshwar.torchglow.ui.TorchGlowApp
import com.eshwar.torchglow.ui.TorchGlowTheme

class MainActivity : ComponentActivity() {

    private lateinit var torchController: TorchController

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        torchController = TorchController(this)

        // A torch app that lets the screen sleep mid-use is no use to anyone.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            TorchGlowTheme {
                TorchGlowApp(controller = torchController)
            }
        }
    }

    override fun onDestroy() {
        // Leave nothing burning once the user actually closes the app.
        if (isFinishing) torchController.turnOff()
        super.onDestroy()
    }
}
