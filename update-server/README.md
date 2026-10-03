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

1. **Create the fly.io app.** Install [flyctl](https://fly.io/docs/flyctl/install/), then:

   ```bash
   fly auth login
   fly apps create reelplay-updates     # must be unique on fly.io
   ```

   If the name is taken, choose another and put it in `fly.toml` (`app = "…"`). The release
   workflow reads it from there and builds the app pointed at `https://<app>.fly.dev/`, so
   that's the only place to change it. Set `primary_region` to the region nearest your users
   (`fly platform regions`).

2. **Give GitHub a deploy token:**

   ```bash
   fly tokens create deploy -a reelplay-updates
   ```

   Add it as the repository secret `FLY_API_TOKEN` (Settings → Secrets and variables →
   Actions).

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

## Publishing a release

1. Bump `versionCode` (and `versionName`) in `player/build.gradle.kts` and push.
2. Actions → **Release ReelPlay** → Run workflow, with the release notes users will see.
   Or push a tag: `git tag -a reelplay-v1.4 -m "What's new…" && git push origin reelplay-v1.4`.

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
