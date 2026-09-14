# Torch Glow

An Android torch app with a colour wheel: one tap for the flashlight, and a
full-screen colour lamp driven by an HSV wheel.

Built for modern Pixels (targets Android API 37, runs on Android 8.0+).

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

### About coloured light

The flash LED on a phone is a white-only emitter — there is no hardware path to tint
it on a Pixel or any other handset, so no app can turn the torch red or blue. The
colour wheel therefore drives the screen, which is the part of the phone that can
actually emit an arbitrary colour. The flashlight and the colour lamp work
independently, and both can be on at once.

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
| `app/src/main/java/com/eshwar/torchglow/ui/TorchGlowApp.kt` | Main screen, torch modes, strobe/SOS timing |
| `app/src/main/java/com/eshwar/torchglow/ui/ColorWheel.kt` | Canvas HSV wheel and its gesture handling |
| `app/src/main/java/com/eshwar/torchglow/ui/ScreenLight.kt` | Full-screen lamp, brightness override, immersive mode |

Kotlin + Jetpack Compose (Material 3), AGP 9.4 with built-in Kotlin support,
Gradle 9.7.

## A note on strobe

Fast strobing light can trigger seizures in people with photosensitive epilepsy.
The app warns about this in the UI; please don't point it at anyone without asking.
