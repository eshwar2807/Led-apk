package com.eshwar.torchglow.hilight

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Version-agnostic face of [HiLightRing].
 *
 * The lights API and everything that touches `android.hardware.lights` lives
 * behind API 37, so the whole ring implementation is gated. This wrapper keeps
 * that gate in one place: the UI can call every method on any Android version
 * and simply sees [RingAccess.UNSUPPORTED_OS] where the hardware API is absent.
 */
class RingController(context: Context) {

    private val ring: HiLightRing? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            HiLightRing(context)
        } else {
            null
        }

    var access: RingAccess by mutableStateOf(RingAccess.UNSUPPORTED_OS)
        private set

    var ledCount: Int by mutableIntStateOf(0)
        private set

    /** Frame interval for animations, never faster than the HAL allows. */
    var frameMillis: Long = 33L
        private set

    /** Last failure from the lights service, shown in the card's diagnostics. */
    var lastError: String? by mutableStateOf(null)
        private set

    /** What the lights service reported, one line per light, before filtering. */
    var report: List<String> by mutableStateOf(emptyList())
        private set

    fun refresh() {
        val target = ring ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        access = target.refresh()
        ledCount = target.ledCount
        frameMillis = target.minUpdatePeriodMillis.coerceAtLeast(33L)
        lastError = target.lastError
        report = target.report
    }

    fun open() {
        val target = ring ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        target.open()
    }

    fun apply(colors: IntArray) {
        val target = ring ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        target.apply(colors)
    }

    fun clear() {
        val target = ring ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        target.clear()
    }

    fun close() {
        val target = ring ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        target.close()
    }

    fun requestShizukuPermission(requestCode: Int) {
        val target = ring ?: return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        target.requestShizukuPermission(requestCode)
    }
}

@Composable
fun rememberRingController(): RingController {
    val context = LocalContext.current
    return remember(context) { RingController(context) }
}
