# Torch Glow

An Android torch app with a colour wheel. One tap for the flashlight, and one
colour driving three outputs: the rear RGB LED ring, a full-screen lamp, and the
wheel itself.

Built for the Pixel 11 Pro family (targets Android API 37, runs on Android 8.0+).

## What it does

**Flashlight**
- Big power button that toggles the rear flash via `CameraManager.setTorchMode()` —
  no camera permission needed, so the app asks for nothing at install time.
- **Intensity slider** on Android 13+, using `turnOnTorchWithStrengthLevel()`. Recent
  Pixels expose multiple strength levels; devices that don't say so in the UI.
- **Steady / Strobe / SOS** modes. Strobe rate is adjustable from 0.5 to 20 Hz;
  SOS blinks the real Morse pattern.
- Stays in sync with the rest of the system — if the quick-settings tile or another
  app kills the torch, the UI drops back to off instead of lying.

**Colour**
- HSV colour wheel: angle picks the hue, distance from the centre picks saturation,
  plus a separate brightness slider and nine one-tap presets.
- Live hex / HSV readout.
- **Full-screen lamp**: the chosen colour fills the display at maximum brightness in
  immersive mode. Tap to hide the controls, double-tap or press Back to exit.

**Rear LED ring (HiLight)**
- The Pixel 11 Pro, Pro XL and Pro Fold ring the rear camera with **eight
  individually addressable RGB LEDs** behind a frosted diffuser — Google's "HiLight",
  surfaced in the UI as Pixel Glow. Stock software only offers a handful of preset
  colours on a few triggers; the hardware does the whole spectrum, per LED.
- Torch Glow paints the wheel colour straight onto them, with four modes: **Solid**,
  **Breathe**, **Chase** (a bright head with a fading tail running round the circle)
  and **Spectrum** (the full hue circle smeared around the ring and rotating).
- Effects are computed per frame in the app rather than handed to the HAL, so they
  adapt to whatever LED count the device reports.

### Getting access to the ring

Android drives these LEDs through `android.hardware.lights.LightsManager` — public
API since 31, with per-light colour and multi-light effects added in API 37. Every
call on that binder is guarded by `android.permission.CONTROL_DEVICE_LIGHTS`, which
`frameworks/base` declares as `signature|privileged`. A sideloaded app cannot hold
it, and `adb shell pm grant` will not hand it over either (that only works for
runtime and development permissions).

So the app tries two routes, in order:

1. **Direct** — the plain SDK path, for a platform-signed or privileged install.
2. **Shizuku** — the same binder calls relayed through [Shizuku](https://shizuku.rikka.app/),
   which runs as the shell user and does hold the permission. Install Shizuku, start
   it over ADB or wireless debugging, then open Torch Glow and tap **Authorise via
   Shizuku**. Shizuku must be restarted after every reboot.

The card in the app tells you which route is live, or exactly what is missing.

The main flash LED is a separate, fixed-colour white emitter, so the torch section
stays white; colour goes to the ring and the screen. All three outputs work
independently and can run at once.

## Install

Download `app-release.apk` (or `app-debug.apk`) and either:

```bash
adb install -r app-release.apk
```

or copy it to the phone and open it, allowing "install unknown apps" for your file
manager when prompted.

Both APKs are signed with the standard Android debug key, which is enough to
sideload. Replace `signingConfig` in `app/build.gradle.kts` with your own keystore
before publishing anywhere.

## Build from source

Requires JDK 21 and the Android SDK (platform 37, build-tools 37.0.0).

```bash
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease      # app/build/outputs/apk/release/app-release.apk
./gradlew lintDebug
```

CI (`.github/workflows/android.yml`) builds both APKs on every push and uploads them
as workflow artifacts.

## Layout

| Path | What's in it |
| --- | --- |
| `app/src/main/java/com/eshwar/torchglow/MainActivity.kt` | Activity, edge-to-edge setup, torch shutdown on exit |
| `app/src/main/java/com/eshwar/torchglow/torch/TorchController.kt` | Camera2 torch wrapper: flash lookup, strength levels, callbacks |
| `app/src/main/java/com/eshwar/torchglow/hilight/HiLightRing.kt` | The LED ring: framework and Shizuku backends, light discovery |
| `app/src/main/java/com/eshwar/torchglow/hilight/RingController.kt` | API-37 gate, so the rest of the app is version-agnostic |
| `app/src/main/java/com/eshwar/torchglow/hilight/RingEffects.kt` | Per-LED colours for solid, breathe, chase and spectrum |
| `app/src/main/java/com/eshwar/torchglow/ui/TorchGlowApp.kt` | Main screen, torch modes, strobe/SOS timing |
| `app/src/main/java/com/eshwar/torchglow/ui/ColorWheel.kt` | Canvas HSV wheel and its gesture handling |
| `app/src/main/java/com/eshwar/torchglow/ui/ScreenLight.kt` | Full-screen lamp, brightness override, immersive mode |

Kotlin + Jetpack Compose (Material 3), AGP 9.4 with built-in Kotlin support,
Gradle 9.7.

## A note on strobe

Fast strobing light can trigger seizures in people with photosensitive epilepsy.
The app warns about this in the UI; please don't point it at anyone without asking.
