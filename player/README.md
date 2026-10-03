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

**Seek bar**
- A thin seek bar in the style of streaming apps: a 3 dp line with a small dot that both grow
  while you drag, with buffered video shown behind the played part. Tap anywhere on it to jump.
- Netflix red by default. **Settings → Seek bar colour** offers nine colours, with a live
  preview; the choice also tints the buffering bar and speed badge.

**Controls**
- Lock (blocks every gesture and the back button), aspect ratio (fit / crop / stretch),
  orientation (landscape / portrait / auto — videos open in their own orientation).
- Playback speed 0.25×–4× (remembered), audio track and subtitle track pickers,
  load external subtitles (`.srt`, `.vtt`, `.ass/.ssa`, `.ttml`).
- Repeat off / one / all, shuffle, sleep timer (minutes or end of video).
- Picture-in-picture (auto-enters on Home while playing on Android 12+).
- Remembers where you left each video; next/previous through the folder.
- **Edit** button sends the current video straight into the editor.

**Torrents and magnet links**
- Library ⋮ → **Open torrent / magnet**: paste a magnet link or a `.torrent` URL, or pick a
  `.torrent` file. Magnet links clicked in a browser and `.torrent` files opened from a file
  manager (or shared to ReelPlay) land in the same place.
- After the torrent's details arrive (from peers, for a magnet), choose **Stream** or
  **Download**.

**Torrent streaming**
- Pick a video and only that file is downloaded.
- **Starts when it can play to the end without stopping.** The buffering screen fetches the
  file's start and end first (MP4/MKV keep their index there), reads the real duration from
  them, and works out the video's bitrate. If the download is faster than playback, a
  20-second cushion is enough; if it's slower, it buffers enough that the rest arrives before
  playback catches up (`StreamReadiness`, using the last ~15 s of download speed with a 25%
  safety margin). It shows "Ready to play without stopping in about …" and starts by itself.
  **Play now** skips the wait.
- Piece deadlines are sized from the measured download speed, so big torrents (with 8–16 MB
  pieces) aren't swamped by deadlines nobody could meet.
- If the torrent hits an error (for example the disk fills up), the player says so instead of
  buffering forever.
- The file downloads front to back while you watch. Seeking jumps the queue: the pieces at
  the new position get deadlines so libtorrent fetches them before anything else, and the
  player waits on just those.
- Download speed, peers and progress show under the title, and on screen whenever playback
  is waiting for data.
- Closing the video stops the torrent and **deletes what it downloaded**. Leftovers from a
  killed app are cleared the next time a torrent starts.
**Torrent downloads**
- Tick the files to keep (all by default) and tap **Download**. Library ⋮ → **Downloads**
  lists everything with progress, speed, peers and time left.
- Keeps going with the app in the background, with a progress notification and a
  "Download complete" one. (Android 15+ limits this kind of background work to 6 hours a day;
  past that, downloads continue whenever the app is open.)
- Pause, resume, retry and remove each download. Unfinished downloads are picked up again the
  next time the app starts; libtorrent re-checks what's already on disk first.
- **Play while downloading**: videos in an unfinished download can be played straight away,
  using the same streaming path (seeks fetch the needed pieces first).
- When done, files are copied to **Download/ReelPlay/<torrent name>/** (keeping the torrent's
  folders), where galleries, file managers and the ReelPlay library see them. The working copy
  is then deleted and seeding stops. Removing a finished download can optionally delete the
  saved files too.
- On Android 8–9 this needs storage permission; without it, finished files stay in
  ReelPlay's own storage and can still be played, opened and shared from Downloads.

**Storage and stability**
- libtorrent runs with plain file I/O rather than its default memory-mapped files. With
  memory-mapping, transferring a 1.5 GB torrent crashed the process with SIGSEGV in local
  tests, both times it was tried; with file I/O the same transfer finished using about 150 MB
  of memory. On a phone, memory-mapping a multi-GB torrent also invites the low-memory
  killer, runs 32-bit phones out of address space, and turns a full disk into a crash.
- Streams and downloads check free space up front and refuse with sizes ("needs 14 GB, 9 GB
  free") instead of filling the disk. If there isn't room to copy a finished download into
  Download/, it stays in ReelPlay's storage and is still playable and shareable.
- Errors in background download bookkeeping are logged instead of crashing the app.
- **Crash reports**: if ReelPlay stopped abnormally (crash, native crash, freeze, or killed
  for memory), the next launch says so and offers **Share report**. On Android 11+ this also
  covers crashes Java can't catch. After such a stop, unfinished downloads come back paused,
  so a download that caused the crash can't crash every launch.

**Torrent engine**
- Built on [libtorrent4j](https://github.com/aldenml/libtorrent4j) (libtorrent 2.0), DHT and
  local peer discovery on. Native code is included for arm64 and 32-bit arm phones; the
  feature reports itself unavailable on x86 devices and emulators.
- Only stream content you have the right to share. Like any BitTorrent client, ReelPlay
  uploads pieces to other peers while it downloads.

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

## Updates

ReelPlay updates itself from a small server on fly.io (`update-server/`). It checks on launch
and every 6 hours in the background, notifies once per new version, downloads and verifies
the APK, and hands it to Android's installer. **Library ⋮ → Check for updates** or
**Settings** checks immediately. Publishing a release is one workflow run; see
[`update-server/README.md`](../update-server/README.md) for the one-time fly.io and signing
key setup.

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
| `torrent/TorrentEngine.kt` | The libtorrent session: reading torrents/magnets, starting and removing streams |
| `torrent/TorrentStream.kt` | Piece maths, pre-buffer, seek deadlines, blocking reads |
| `torrent/TorrentDataSource.kt` | Media3 `DataSource` that plays `torrent://` URIs from the partial file |
| `torrent/TorrentActivity.kt` | Fetch details, then stream a video (buffer, hand off) or pick files to download |
| `torrent/TorrentDownloads.kt` | Download list: adding, pause/resume, progress, saving to Download/, restart recovery |
| `torrent/TorrentDownloadService.kt` | Foreground service and notifications while downloads run |
| `torrent/DownloadsActivity.kt` | The Downloads screen |
| `player/SeekBar.kt` | The thin seek bar |
| `settings/` | Settings screen and stored preferences (seek bar colour) |
| `update/` | Update check, background worker, download + install, update dialog |
| `CrashReporter.kt` | Records crashes and abnormal exits; shown on the next launch |
| `editor/Project.kt` | Immutable project model: clips, canvas, music |
| `editor/EditorState.kt` | Undo/redo around the project |
| `editor/CompositionFactory.kt` | Project → Media3 `Composition` (effects, text, speed, music) |
| `editor/Exporter.kt` | Transformer export and saving to the gallery |
| `editor/EditorScreen.kt`, `EditorPanels.kt` | Preview, timeline, toolbars and tool panels |

## Tests

`./gradlew :player:testDebugUnitTest` runs `TorrentStreamTest`. It seeds a real
multi-file torrent from one libtorrent session and streams a file from a second session over
localhost, with the seeder throttled. The reads (start, tail, then seeks into the middle) have
to wait for missing pieces and must return the original bytes.

`StreamReadinessTest` checks the start-up maths: a fast download needs only the 20 s cushion;
a slow one (1 MB/s against a 14 GB, 2 h 20 min film) buffers enough that a simulated
playback never overtakes the download; no data means no ETA.

A second libtorrent test does the download path: libtorrent's default add flags, then taken off the
queue manager and paused, as `TorrentDownloads` does. It checks nothing downloads while
paused, that after resuming only the chosen file is fetched, and that "finished" means that
file is complete and byte-identical. A third checks the saved download list survives a JSON
round trip. The libtorrent tests use the same posix disk backend as the app, and run on Linux x86_64
only (they skip elsewhere).

## Limits

- Text and filters apply to whole clips; split a clip to time a caption.
- No transitions, stickers, keyframes or reverse yet.
- Streams aren't kept after playback (use Download for that). Finished downloads aren't
  seeded. There are no speed limits, Wi-Fi-only mode or download queue yet; every download
  runs at once. Editing a video that's still streaming isn't supported.
- MX's software decoders (for codecs the phone can't decode in hardware, e.g. some AC3/DTS
  audio) aren't included; playback uses the phone's own decoders.
