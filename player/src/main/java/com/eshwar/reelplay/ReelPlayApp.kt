package com.eshwar.reelplay

import android.app.Application
import com.eshwar.reelplay.torrent.TorrentDownloadService
import com.eshwar.reelplay.torrent.TorrentDownloads
import java.io.File
import kotlin.concurrent.thread

class ReelPlayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Pick unfinished torrent downloads back up. Only if there are any: this loads the
        // native torrent engine, which nobody who never downloads should pay for.
        if (File(filesDir, "downloads/registry.json").exists()) {
            thread(name = "torrent-restore") {
                try {
                    TorrentDownloads.restore(this)
                    if (TorrentDownloads.hasActive()) TorrentDownloadService.start(this)
                } catch (_: LinkageError) {
                    // No native engine for this CPU; the Downloads screen says so.
                } catch (_: Exception) {
                }
            }
        }
    }
}
