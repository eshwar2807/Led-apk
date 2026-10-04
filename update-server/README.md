# ReelPlay update server

A small Go server, hosted on [fly.io](https://fly.io), that tells installed copies of ReelPlay
when a new version is out and serves the APK.

| URL | What |
| --- | --- |
| `/reelplay/latest.json` | Manifest the app polls: version, APK name, size, SHA-256, notes |
| `/reelplay/ReelPlay-<versionCode>.apk` | The APK (resumable downloads) |
| `/` | A download page for people who don't have the app yet |
| `/healthz` | Health check |

Each deploy carries exactly one release, written into `release/` by the
**Release ReelPlay** workflow and baked into the image. On startup the server checks the APK
against the manifest's size and SHA-256 and refuses to start if they don't match, so fly.io
keeps the previous version running instead of serving a broken one.

## How the app uses it

- Checks on every launch, and every 6 hours in the background (WorkManager, only on a
  network). A new version posts one notification; tapping it opens the update dialog.
- **Library ⋮ → Check for updates** or **Settings → Check for updates** checks immediately.
- **Update** downloads the APK (resuming if interrupted), verifies size and SHA-256, and hands
  it to Android's package installer. Android shows its own confirmation and installs it only
  if it's signed with the same key as the installed app.
- The first time, Android asks to allow "Install unknown apps" for ReelPlay.

These are polling checks, not push notifications. Push would need Firebase Cloud Messaging
and a Google account setup; a 6-hourly check is free and needs nothing else.

## One-time setup

1. **Get a fly.io token.** Sign up at fly.io, install
   [flyctl](https://fly.io/docs/flyctl/install/), then:

   ```bash
   fly auth login
   fly tokens create org
   ```

   Add the token as the repository secret `FLY_API_TOKEN` (Settings → Secrets and variables →
   Actions). The release workflow creates the fly.io app on its first run.

   The app is named by `app = "…"` in `fly.toml` (default `reelplay-updates`); names are
   global on fly.io, so if it's taken, pick another there. The workflow builds the phone app
   pointed at `https://<app>.fly.dev/`, so that line is the only place to change it. Set
   `primary_region` to the region nearest your users (`fly platform regions`).

2. *(Optional, tighter access)* After the first release, swap the org token for a deploy
   token limited to this app: `fly tokens create deploy -a reelplay-updates`.

3. **Create a release signing key** and keep a backup somewhere safe. Every future update
   must be signed with it, or phones will refuse to install it over the existing app.

   ```bash
   keytool -genkeypair -v -keystore reelplay-release.keystore -alias reelplay \
     -keyalg RSA -keysize 4096 -validity 10000
   base64 -w0 reelplay-release.keystore     # macOS: base64 -i reelplay-release.keystore
   ```

   Add the repository secrets `RELEASE_KEYSTORE_BASE64` (that base64 output),
   `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` (`reelplay`) and `RELEASE_KEY_PASSWORD`.

   Copies of ReelPlay installed before this key existed were signed with a development key,
   so Android won't install the first proper release over them. Uninstall once and install
   from `https://<app>.fly.dev/`; every update after that installs in place. (The app
   explains this if it happens.)

Until the first release is published, **Check for updates** in the app reports that the
update server doesn't exist yet. That's expected.

## Publishing a release

1. Bump `versionCode` (and `versionName`) in `player/build.gradle.kts` and push.
2. Write what's new in `player/RELEASE_NOTES.md` (users see it in the update prompt) and push.
   Changing that file starts the release. You can also do it in the GitHub web editor.
   Alternatives: Actions → **Release ReelPlay** → Run workflow, or push a tag such as
   `reelplay-v1.4` (its message becomes the notes).

The workflow builds and signs the APK, runs the tests, refuses to publish unless the
versionCode is higher than the live one, deploys, then checks the server reports the new
version. Phones pick it up on their next check.

## Running it locally

```bash
go test ./...
mkdir -p /tmp/rel && cp ReelPlay-5.apk /tmp/rel/   # plus a matching latest.json
RELEASE_DIR=/tmp/rel PORT=8080 go run .
```

Build an app that checks a local or staging server with
`./gradlew :player:assembleDebug -PreelplayUpdateUrl=https://staging.example/reelplay/`.

## Problem reports

The app's **Report a problem** (and the crash, playback-error and slow-torrent screens) POST to
`/reelplay/report`. Each report is saved as a JSON file on the `reports` fly.io volume (1 GB),
which the release workflow creates on first deploy. Senders are limited to 20 reports an hour
per IP, 256 KB each.

To read them, add a repository secret **REPORTS_KEY** (any password you choose). The next
release passes it to the server. Then open:

    https://reelplay-updates.fly.dev/reelplay/reports?key=<REPORTS_KEY>

Without the key, reading is switched off, but reports are still stored, and every report's
summary also appears in `fly logs`. If the volume can't be created, the release still deploys
and reports are only logged.
