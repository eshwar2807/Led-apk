package com.eshwar.torchglow.torch

import android.content.Context
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.util.Log

/**
 * Thin wrapper around [CameraManager]'s torch API.
 *
 * `setTorchMode()` needs no runtime permission, so the app never has to ask for
 * the camera. On Android 13+ the flash unit can also report a strength range,
 * which is what powers the intensity slider on recent Pixels.
 */
class TorchController(context: Context) {

    private val cameraManager = context.applicationContext
        .getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /** First back-facing camera that owns a flash unit, falling back to any flash camera. */
    private val flashCameraId: String? = findFlashCamera()

    val isAvailable: Boolean get() = flashCameraId != null

    /** Highest strength level the flash accepts; 1 means "on/off only". */
    val maxStrengthLevel: Int = flashCameraId?.let { id ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@let 1
        runCatching {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
        }.getOrDefault(1)
    } ?: 1

    /** Level the system uses for a plain torch toggle. */
    val defaultStrengthLevel: Int = flashCameraId?.let { id ->
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@let 1
        runCatching {
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: 1
        }.getOrDefault(1)
    } ?: 1

    val supportsStrength: Boolean get() = maxStrengthLevel > 1

    private var lastAppliedLevel = -1

    /**
     * Turns the flash on or off.
     *
     * @param level desired strength in `1..maxStrengthLevel`; ignored where the
     *              hardware or OS version has no strength control.
     * @return true when the command reached the camera service.
     */
    fun setTorch(on: Boolean, level: Int = defaultStrengthLevel): Boolean {
        val id = flashCameraId ?: return false
        return try {
            if (on && supportsStrength && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val safeLevel = level.coerceIn(1, maxStrengthLevel)
                cameraManager.turnOnTorchWithStrengthLevel(id, safeLevel)
                lastAppliedLevel = safeLevel
            } else {
                cameraManager.setTorchMode(id, on)
                if (!on) lastAppliedLevel = -1
            }
            true
        } catch (e: CameraAccessException) {
            // Another app holds the camera, or the torch is temporarily unavailable.
            Log.w(TAG, "Torch command failed", e)
            false
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Torch command rejected", e)
            false
        }
    }

    /** Re-applies the strength while the torch is already lit. */
    fun updateStrength(level: Int) {
        if (!supportsStrength) return
        if (level.coerceIn(1, maxStrengthLevel) == lastAppliedLevel) return
        setTorch(on = true, level = level)
    }

    fun turnOff() {
        setTorch(on = false)
    }

    /** Observes torch changes made elsewhere (quick settings tile, other apps). */
    fun registerTorchCallback(onChanged: (enabled: Boolean) -> Unit): CameraManager.TorchCallback? {
        val id = flashCameraId ?: return null
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId == id) onChanged(enabled)
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (cameraId == id) onChanged(false)
            }
        }
        return runCatching {
            cameraManager.registerTorchCallback(callback, null)
            callback
        }.getOrNull()
    }

    fun unregisterTorchCallback(callback: CameraManager.TorchCallback?) {
        callback ?: return
        runCatching { cameraManager.unregisterTorchCallback(callback) }
    }

    private fun findFlashCamera(): String? = runCatching {
        val ids = cameraManager.cameraIdList
        ids.firstOrNull { id ->
            val chars = cameraManager.getCameraCharacteristics(id)
            chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: ids.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private companion object {
        const val TAG = "TorchController"
    }
}
