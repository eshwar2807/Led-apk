package com.eshwar.torchglow.hilight

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.lights.Light
import android.hardware.lights.LightState
import android.hardware.lights.LightsManager
import android.hardware.lights.LightsRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import androidx.annotation.RequiresApi
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * Controls the ring of addressable RGB LEDs around the rear camera — "HiLight"
 * on the Pixel 11 Pro family.
 *
 * Android exposes these through [LightsManager] (API 31, with per-light colour
 * and multi-light effects added in API 37), but every call on the lights binder
 * is guarded by `android.permission.CONTROL_DEVICE_LIGHTS`, which is declared
 * `signature|privileged`: a sideloaded app cannot hold it, and `pm grant` will
 * not hand it over either.
 *
 * So there are two ways in, tried in order:
 *  1. [Backend.Framework] — the plain SDK path. Works when the app genuinely holds
 *     the permission: a platform-signed build, a privileged install, or a rooted
 *     device where the owner has put the APK in a privileged directory.
 *  2. [Backend.Shizuku] — relays the same binder calls through Shizuku, which runs
 *     as the shell user and does hold the permission. Shizuku is started by the
 *     phone's owner over ADB and authorises each app individually, so this path
 *     only ever works with the owner's explicit, revocable consent.
 *
 * The Shizuku path writes its binder transactions by hand rather than using the
 * hidden `ILightsManager` stub: `Light` and `LightState` are public SDK
 * parcelables, so nothing here touches a non-SDK class.
 */
@RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
class HiLightRing(private val context: Context) {

    private sealed interface Backend {
        fun lights(): List<Light>
        fun open()
        fun apply(colors: Map<Light, Int?>)
        fun close()

        /** Plain SDK calls — succeeds only when the app itself holds the permission. */
        class Framework(context: Context) : Backend {
            private val manager = context.getSystemService(LightsManager::class.java)
            private var session: LightsManager.LightsSession? = null

            override fun lights(): List<Light> = manager?.lights.orEmpty()

            override fun open() {
                if (session == null) session = manager?.openSession()
            }

            override fun apply(colors: Map<Light, Int?>) {
                val active = session ?: return
                val request = LightsRequest.Builder().apply {
                    colors.forEach { (light, color) ->
                        if (color == null) {
                            clearLight(light)
                        } else {
                            addLight(light, LightState.Builder().setColor(color).build())
                        }
                    }
                }.build()
                active.requestLights(request)
            }

            override fun close() {
                session?.close()
                session = null
            }
        }

        /**
         * The same ILightsManager calls, relayed through Shizuku so they reach the
         * system server as the shell user.
         *
         * Transaction ids follow AIDL declaration order. Methods are appended to an
         * AIDL interface rather than inserted, so these four keep their ids as the
         * interface grows.
         */
        class Shizuku : Backend {
            private val token: IBinder = Binder()
            private var opened = false

            private val binder: IBinder?
                get() = runCatching {
                    SystemServiceHelper.getSystemService(LIGHTS_SERVICE)
                        ?.let { ShizukuBinderWrapper(it) }
                }.getOrNull()

            override fun lights(): List<Light> = callForResult(TRANSACTION_GET_LIGHTS) { reply ->
                reply.createTypedArrayList(Light.CREATOR).orEmpty()
            }

            override fun open() {
                if (opened) return
                call(TRANSACTION_OPEN_SESSION) { data ->
                    data.writeStrongBinder(token)
                    data.writeInt(SESSION_PRIORITY)
                }
                opened = true
            }

            override fun apply(colors: Map<Light, Int?>) {
                if (!opened) return
                val ids = colors.keys.map { it.id }.toIntArray()
                val states = colors.values
                    .map { color -> color?.let { LightState.Builder().setColor(it).build() } }
                    .toTypedArray()
                call(TRANSACTION_SET_LIGHT_STATES) { data ->
                    data.writeStrongBinder(token)
                    data.writeIntArray(ids)
                    data.writeTypedArray(states, 0)
                }
            }

            override fun close() {
                if (!opened) return
                call(TRANSACTION_CLOSE_SESSION) { data -> data.writeStrongBinder(token) }
                opened = false
            }

            /**
             * Failures are thrown rather than swallowed: [HiLightRing] catches them in
             * one place and keeps the message, so the UI can say what actually broke
             * instead of quietly reporting an empty ring.
             */
            private fun <T> callForResult(
                code: Int,
                write: (Parcel) -> Unit = {},
                read: (Parcel) -> T,
            ): T {
                val target = binder ?: error("Shizuku did not hand back the lights service")
                val data = Parcel.obtain()
                val reply = Parcel.obtain()
                return try {
                    data.writeInterfaceToken(DESCRIPTOR)
                    write(data)
                    target.transact(code, data, reply, 0)
                    reply.readException()
                    read(reply)
                } finally {
                    reply.recycle()
                    data.recycle()
                }
            }

            private fun call(code: Int, write: (Parcel) -> Unit = {}) {
                callForResult(code, write) { }
            }

            private companion object {
                const val LIGHTS_SERVICE = "lights"
                const val DESCRIPTOR = "android.hardware.lights.ILightsManager"

                // IBinder.FIRST_CALL_TRANSACTION + AIDL declaration order.
                const val TRANSACTION_GET_LIGHTS = IBinder.FIRST_CALL_TRANSACTION
                const val TRANSACTION_OPEN_SESSION = IBinder.FIRST_CALL_TRANSACTION + 2
                const val TRANSACTION_CLOSE_SESSION = IBinder.FIRST_CALL_TRANSACTION + 3
                const val TRANSACTION_SET_LIGHT_STATES = IBinder.FIRST_CALL_TRANSACTION + 4

                /** Above the default of 0, so our colours win over idle system requests. */
                const val SESSION_PRIORITY = 1
            }
        }
    }

    private var backend: Backend? = null

    /** Ring LEDs in ordinal order — ordinal walks around the circle. */
    var lights: List<Light> = emptyList()
        private set

    var access: RingAccess = RingAccess.UNSUPPORTED_OS
        private set

    /** Last failure from the lights service, for the diagnostics line in the UI. */
    var lastError: String? = null
        private set

    /**
     * One line per light the service reported, before filtering. The ring's exact
     * type and capability flags are device-specific, so when nothing matches this
     * says what the device actually offered.
     */
    var report: List<String> = emptyList()
        private set

    val ledCount: Int get() = lights.size

    /** Fastest safe update interval for animation, from what the HAL reports. */
    val minUpdatePeriodMillis: Long
        get() = lights.maxOfOrNull { it.minUpdatePeriodMillis }?.coerceAtLeast(16L) ?: 33L

    /**
     * Works out which backend (if any) can drive the ring. Safe to call repeatedly —
     * the owner may install or authorise Shizuku while the app is open.
     */
    fun refresh(): RingAccess {
        lastError = null

        // 1. Straight SDK path. A plain install is refused here, which is expected
        //    and not worth reporting — the Shizuku attempt below is the real answer.
        val framework = Backend.Framework(context)
        val frameworkLights = runCatching { framework.lights() }.getOrElse { emptyList() }
        val frameworkRing = frameworkLights.filterRing()
        if (frameworkRing.isNotEmpty()) {
            backend = framework
            lights = frameworkRing
            report = frameworkLights.map { it.describe() }
            access = RingAccess.DIRECT
            return access
        }

        // 2. Shizuku relay.
        val shizukuState = shizukuState()
        if (shizukuState != RingAccess.SHIZUKU) {
            access = shizukuState
            return access
        }
        val shizuku = Backend.Shizuku()
        val available = runCatching { shizuku.lights() }
            .onFailure {
                Log.w(TAG, "Could not list lights through Shizuku", it)
                lastError = it.describe()
            }
            .getOrNull()

        if (available == null) {
            // The relay itself failed — say so rather than claiming the device has
            // no ring, which is a different problem with a different fix.
            report = emptyList()
            access = RingAccess.RELAY_FAILED
            return access
        }

        report = available.map { it.describe() }
        val ring = available.filterRing()
        access = if (ring.isEmpty()) {
            RingAccess.NO_RING
        } else {
            backend = shizuku
            lights = ring
            RingAccess.SHIZUKU
        }
        return access
    }

    fun open() {
        runCatching { backend?.open() }
            .onFailure {
                Log.w(TAG, "Could not open a lights session", it)
                lastError = it.describe()
            }
    }

    /** Paints [colors] onto the ring, in the order [lights] reports. */
    fun apply(colors: IntArray) {
        if (colors.size != lights.size) return
        apply(lights.mapIndexed { index, light -> light to colors[index] }.toMap())
    }

    /** Paints one colour per LED; a null entry releases that LED. */
    fun apply(colors: Map<Light, Int?>) {
        runCatching { backend?.apply(colors) }
            .onFailure {
                Log.w(TAG, "Failed to apply ring colours", it)
                lastError = it.describe()
            }
    }

    fun clear() = apply(lights.associateWith { null })

    fun close() {
        runCatching { backend?.close() }
    }

    /**
     * Keep the lights an app may paint: not one of the dedicated indicators
     * (microphone, player id, keyboard backlight, input device).
     *
     * Colour-capable lights are preferred, but a HAL that under-reports its
     * capabilities is a real possibility, so rather than show an empty ring we fall
     * back to whatever non-reserved lights the service offered and let the colours
     * speak for themselves.
     */
    private fun List<Light>.filterRing(): List<Light> {
        val candidates = filter { it.type !in RESERVED_TYPES }.sortedBy { it.ordinal }
        return candidates.filter { it.hasRgbControl() }.ifEmpty { candidates }
    }

    private fun Light.describe(): String = buildString {
        append("id=").append(id)
        append(" type=").append(type)
        append(" ord=").append(ordinal)
        append(if (hasRgbControl()) " rgb" else " no-rgb")
        if (hasBrightnessControl()) append(" brightness")
        if (hasAnimationControl()) append(" animation")
        append(" min=").append(minUpdatePeriodMillis).append("ms")
    }

    private fun Throwable.describe(): String =
        "${this::class.java.simpleName}: ${message ?: "no detail"}"

    private fun shizukuState(): RingAccess = try {
        when {
            !Shizuku.pingBinder() -> RingAccess.SHIZUKU_UNAVAILABLE
            Shizuku.isPreV11() -> RingAccess.SHIZUKU_UNAVAILABLE
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> RingAccess.SHIZUKU
            else -> RingAccess.SHIZUKU_NEEDS_PERMISSION
        }
    } catch (e: Throwable) {
        // With Shizuku absent entirely, its binder classes throw rather than answer.
        Log.d(TAG, "Shizuku unavailable", e)
        RingAccess.SHIZUKU_UNAVAILABLE
    }

    fun requestShizukuPermission(requestCode: Int) {
        runCatching { Shizuku.requestPermission(requestCode) }
    }

    private companion object {
        const val TAG = "HiLightRing"

        val RESERVED_TYPES = setOf(
            Light.LIGHT_TYPE_MICROPHONE,
            Light.LIGHT_TYPE_PLAYER_ID,
            Light.LIGHT_TYPE_KEYBOARD_BACKLIGHT,
            Light.LIGHT_TYPE_INPUT,
        )
    }
}
