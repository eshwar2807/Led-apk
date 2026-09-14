package com.eshwar.torchglow.hilight

/**
 * Whether — and by what route — this build can drive the device's RGB light ring.
 *
 * Kept out of [HiLightRing] so the UI can talk about the ring on any Android
 * version, including the ones where the lights API does not exist at all.
 */
enum class RingAccess {
    /** Device is older than the API that exposes per-light colour. */
    UNSUPPORTED_OS,

    /** No RGB-capable, app-controllable light on this device. */
    NO_RING,

    /**
     * Shizuku authorised the app, but the call to the lights service failed —
     * a different problem from the device having no ring, so it reads differently.
     */
    RELAY_FAILED,

    /** The app holds CONTROL_DEVICE_LIGHTS itself. */
    DIRECT,

    /** Shizuku is running and has authorised this app. */
    SHIZUKU,

    /** Shizuku is running but has not authorised this app yet. */
    SHIZUKU_NEEDS_PERMISSION,

    /** Shizuku is not installed, or not started since the last reboot. */
    SHIZUKU_UNAVAILABLE;

    val isReady: Boolean get() = this == DIRECT || this == SHIZUKU
}
