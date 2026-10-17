package com.eshwar.reelplay

import android.app.Application
import android.util.Log
import com.eshwar.reelplay.faces.FaceIndex
import com.eshwar.reelplay.settings.AppSettings
import com.eshwar.reelplay.settings.Prefs
import com.eshwar.reelplay.torrent.TorrentDownloadService
import com.eshwar.reelplay.torrent.TorrentDownloads
import com.eshwar.reelplay.update.UpdateWorker
import com.eshwar.reelplay.web.YtDlpDownloader
import java.io.File
import kotlin.concurrent.thread

class ReelPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        AppSettings.load(this)
        Prefs.init(this)
        if (Prefs.autoUpdateCheck) UpdateWorker.schedule(this) else UpdateWorker.cancel(this)
        // People: pick up photos and videos added since last time.
        FaceIndex.scanInBackground(this)

        // Initialize yt-dlp & FFmpeg engine (Seal's downloader) on a background thread.
        thread(name = "ytdlp-init") {
            try {
                YtDlpDownloader.init(this)
            } catch (e: Exception) {
                Log.w("ReelPlayApp", "Failed to init YtDlpDownloader", e)
            }
        }

        // Pick unfinished torrent downloads back up. Only if there are any: this loads the
        // native torrent engine, which nobody who never downloads should pay for.
        if (File(filesDir, "downloads/registry.json").exists()) {
            thread(name = "torrent-restore") {
                try {
                    // If the last run crashed, bring downloads back paused: if one of them caused
                    // it, restarting it automatically would crash again on every launch.
                    TorrentDownloads.restore(this, startPaused = CrashReporter.lastRunCrashed(this))
                    if (TorrentDownloads.hasActive()) TorrentDownloadService.start(this)
                } catch (_: LinkageError) {
                    // No native engine for this CPU; the Downloads screen says so.
                } catch (_: Exception) {
                }
            }
        }
    }
}
