# ReelPlay

A video player in the spirit of MX Player, with a CapCut-style editor built in.
Separate app from Torch Glow (`com.eshwar.reelplay`), living in the `:player` module.

Built on AndroidX Media3 1.11: ExoPlayer for playback, `CompositionPlayer` for the
editor's live preview and `Transformer` for export, so the preview and the exported file
come from the same composition.

## Player

**Library**
- Every video on the phone, grouped by folder or as one list, read from MediaStore.
- Sort by date, name, size or length; search by name.
- Thumbnails with duration and a resume-progress strip, "Continue watching" banner.
- Per-video menu: play, edit, share, properties, delete (Android 11+, via the system dialog).
- Network stream: paste an `http(s)` link to an MP4/WebM file.
- Opens from other apps' "Open with" (`ACTION_VIEW video/*`).

**Gestures**
- Swipe up/down on the **left** half for brightness, on the **right** half for volume.
  Keep going past 100% for a **volume boost** up to 200% (LoudnessEnhancer, +12 dB).
- Swipe left/right to seek (about 90 s per screen width), with a live readout.
- Double-tap left/right third to skip ±10 s, double-tap the middle to pause/play.
- Pinch to zoom (50–400%).

**Controls**
- Lock (blocks every gesture and the back button), aspect ratio (fit / crop / stretch),
  orientation (landscape / portrait / auto — videos open in their own orientation).
- Playback speed 0.25×–4× (remembered), audio track and subtitle track pickers,
  load external subtitles (`.srt`, `.vtt`, `.ass/.ssa`, `.ttml`).
- Repeat off / one / all, shuffle, sleep timer (minutes or end of video).
- Picture-in-picture (auto-enters on Home while playing on Android 12+).
- Remembers where you left each video; next/previous through the folder.
- **Edit** button sends the current video straight into the editor.

## Editor

Open it with **New edit** in the library, **Edit** in a video's menu or in the player, or
by sharing videos to ReelPlay from the gallery.

- Multi-clip timeline of videos **and photos**, scrolled under a fixed playhead
  (drag it to scrub; the preview follows).
- **Split** at the playhead, **Trim** (range slider), **Duplicate**, **Delete**, move
  clips left/right.
- **Speed** 0.25×–4× per clip, **Volume** 0–200% per clip, mute original audio.
- **Filters**: Vivid, Warm, Cool, Vintage, Fade, Drama, B&W, Noir, Invert —
  per clip or applied to all.
- **Adjust**: brightness, contrast, saturation, hue, blur.
- **Rotate** 90°, **Mirror**, **Flip**.
- **Text**: captions with colour, size, top/middle/bottom placement and an optional
  background box, burned into the clip.
- **Audio**: background music from any audio file, volume, fade in/out; loops if the
  video is longer.
- **Format**: Original, 9:16, 1:1, 16:9, 4:5, 4:3, 2.35:1 — clips are fitted and letterboxed.
- Photo duration 0.5–15 s.
- Undo / redo for every change.
- **Export** to H.264 MP4 at 480p, 720p, 1080p or 2K, saved to `Movies/ReelPlay`,
  then play or share it from the dialog.

## Build

```bash
./gradlew :player:assembleDebug     # player/build/outputs/apk/debug/player-debug.apk
./gradlew :player:assembleRelease   # player/build/outputs/apk/release/player-release.apk
```

Both are signed with the debug key, which is fine for sideloading. CI uploads them as the
`reelplay-apks` artifact.

## Layout

| Path | What's in it |
| --- | --- |
| `library/` | MediaStore scan, folder grouping, thumbnails, library screen |
| `player/PlayerActivity.kt` | ExoPlayer, PiP, brightness, boost, orientation, resume |
| `player/PlayerScreen.kt` | Gesture surface, controls, track/speed/sleep dialogs |
| `editor/Project.kt` | Immutable project model: clips, canvas, music |
| `editor/EditorState.kt` | Undo/redo around the project |
| `editor/CompositionFactory.kt` | Project → Media3 `Composition` (effects, text, speed, music) |
| `editor/Exporter.kt` | Transformer export and saving to the gallery |
| `editor/EditorScreen.kt`, `EditorPanels.kt` | Preview, timeline, toolbars and tool panels |

## Limits

- Text and filters apply to whole clips; split a clip to time a caption.
- No transitions, stickers, keyframes or reverse yet.
- MX's software decoders (for codecs the phone can't decode in hardware, e.g. some AC3/DTS
  audio) aren't included; playback uses the phone's own decoders.
