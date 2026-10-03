package com.eshwar.reelplay.torrent

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.settings_pack
import org.libtorrent4j.swig.torrent_flags_t
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.random.Random

/**
 * Seeds a real multi-file torrent from one libtorrent session and streams one file of it from
 * another over localhost, with the seeder throttled so reads genuinely have to wait. Checks
 * that what [TorrentStream] hands back matches the original bytes, including after seeks.
 */
class TorrentStreamTest {

    private lateinit var root: File
    private val sessions = mutableListOf<SessionManager>()

    @Before
    fun setUp() {
        assumeTrue(
            "libtorrent's desktop build is only bundled for Linux x86_64",
            System.getProperty("os.name").orEmpty().startsWith("Linux") &&
                System.getProperty("os.arch").orEmpty() in setOf("amd64", "x86_64"),
        )
        loadNativeLibrary()
        root = Files.createTempDirectory("torrent-test").toFile()
    }

    @After
    fun tearDown() {
        sessions.forEach { runCatching { it.stop() } }
        if (::root.isInitialized) root.deleteRecursively()
    }

    @Test(timeout = 180_000)
    fun streamsFileWhileItDownloads_withSeeks() {
        // Source: a small file ahead of the video, so the video doesn't start on a piece boundary.
        val pack = File(root, "seed/pack").apply { mkdirs() }
        File(pack, "a_readme.txt").writeBytes(Random(1).nextBytes(100_003))
        val video = Random(2).nextBytes(12 * 1024 * 1024 + 777)
        File(pack, "movie.mp4").writeBytes(video)

        val built = TorrentBuilder().path(pack).pieceSize(256 * 1024).generate()
        val torrentBytes = built.entry().bencode()
        val info = TorrentInfo(torrentBytes)
        val meta = TorrentMeta(info, torrentBytes)
        val file = meta.videoFiles.single()
        assertEquals("movie.mp4", file.name)

        // Seeder, throttled to ~3 MB/s so the reader overtakes the download.
        val seedPort = 47000 + Random.nextInt(500)
        val seeder = session(seedPort)
        seeder.download(info, File(root, "seed"))
        val seedHandle = awaitHandle(seeder, info)
        // Per-torrent, because session limits exempt peers on the local network.
        seedHandle.setUploadLimit(3 * 1024 * 1024)
        waitUntil(60_000) { seedHandle.status().state() == TorrentStatus.State.SEEDING }

        // Leecher, set up exactly as TorrentEngine.start does.
        val leecher = session(seedPort + 1)
        val dir = File(root, "leech").apply { mkdirs() }
        val priorities = Array(meta.files.size) { if (it == file.index) Priority.DEFAULT else Priority.IGNORE }
        leecher.download(
            info, dir, null, priorities, listOf(TcpEndpoint("127.0.0.1", seedPort)), TorrentFlags.SEQUENTIAL_DOWNLOAD,
        )
        val stream = TorrentStream(awaitHandle(leecher, info), meta, file, File(dir, file.path), ownsTorrent = true)
        stream.primeEnds()

        // Like a player: header first, then the tail (MP4 index), then seek into the middle.
        waitUntil(60_000) { stream.has(stream.firstPiece) }
        var lastContiguous = 0L
        RandomAccessFile(stream.path, "r").use { raf ->
            for ((start, length) in listOf(
                0L to 600_000,
                video.size - 50_000L to 50_000,
                video.size * 6L / 10 to 900_000,
                video.size * 3L / 10 to 300_000,
            )) {
                val got = readFully(stream, raf, start, length)
                assertArrayEquals(
                    "bytes at $start..${start + length}",
                    video.copyOfRange(start.toInt(), start.toInt() + length),
                    got,
                )
                // What the buffering screen measures only ever grows, and never runs past data we have.
                val contiguous = stream.contiguousBytes()
                assertTrue(contiguous >= lastContiguous)
                lastContiguous = contiguous
            }
            assertEquals(-1, stream.read(raf, video.size.toLong(), ByteArray(16), 0, 16))
            waitUntil(60_000) { stream.contiguousBytes() == video.size.toLong() }
            assertTrue(stream.endsReady())
            assertEquals(null, stream.error())
        }
    }

    /**
     * The download path: added unpaused-by-us with libtorrent's default flags, taken off the
     * auto-manager the way [TorrentDownloads] does, then paused and resumed. Checks pause really
     * holds, only the chosen file is fetched, and "finished" means the chosen file is complete.
     */
    @Test(timeout = 180_000)
    fun downloadsOnlyChosenFiles_andPauseHolds() {
        val pack = File(root, "seed/pack").apply { mkdirs() }
        File(pack, "a_extra.bin").writeBytes(Random(3).nextBytes(3 * 1024 * 1024))
        val wanted = Random(4).nextBytes(6 * 1024 * 1024 + 123)
        File(pack, "b_movie.mkv").writeBytes(wanted)
        val torrentBytes = TorrentBuilder().path(pack).pieceSize(256 * 1024).generate().entry().bencode()
        val info = TorrentInfo(torrentBytes)
        val meta = TorrentMeta(info, torrentBytes)
        val chosen = meta.files.single { it.name == "b_movie.mkv" }

        val seedPort = 47600 + Random.nextInt(300)
        val seeder = session(seedPort)
        seeder.download(info, File(root, "seed"))
        val seedHandle = awaitHandle(seeder, info)
        seedHandle.setUploadLimit(2 * 1024 * 1024)
        waitUntil(60_000) { seedHandle.status().state() == TorrentStatus.State.SEEDING }

        val leecher = session(seedPort + 1)
        val dir = File(root, "leech").apply { mkdirs() }
        val priorities = Array(meta.files.size) { if (it == chosen.index) Priority.DEFAULT else Priority.IGNORE }
        leecher.download(info, dir, null, priorities, listOf(TcpEndpoint("127.0.0.1", seedPort)), torrent_flags_t())
        val handle = awaitHandle(leecher, info)
        handle.unsetFlags(TorrentFlags.AUTO_MANAGED)
        handle.pause()

        // Paused: nothing arrives, and libtorrent's queue doesn't quietly restart it.
        Thread.sleep(3_000)
        assertEquals(0L, handle.status().totalWantedDone())

        handle.resume()
        waitUntil(120_000) { handle.status().isFinished }
        val status = handle.status()
        assertEquals(chosen.size, status.totalWanted())
        assertEquals(chosen.size, status.totalWantedDone())
        assertArrayEquals(wanted, File(dir, chosen.path).readBytes())
        // The skipped file never gets written out in full.
        val extra = File(dir, meta.files.single { it.name == "a_extra.bin" }.path)
        assertTrue(!extra.exists() || extra.length() < 3 * 1024 * 1024)
    }

    @Test
    fun downloadRecordSurvivesJsonRoundTrip() {
        val record = DownloadRecord(
            id = "abc123", name = "Some \"torrent\" / name", selected = listOf(0, 2, 5),
            totalBytes = 9_876_543_210, addedAt = 1_700_000_000_000, paused = true, done = true,
            saved = listOf(SavedFile("a.mkv", "content://media/external/downloads/42", "video/x-matroska")),
            error = null, location = "Download/ReelPlay/x",
        )
        assertEquals(record, DownloadRecord.fromJson(org.json.JSONObject(record.toJson().toString())))
        val bare = record.copy(saved = emptyList(), done = false, location = null, error = "boom")
        assertEquals(bare, DownloadRecord.fromJson(org.json.JSONObject(bare.toJson().toString())))
    }

    private fun readFully(stream: TorrentStream, raf: RandomAccessFile, start: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = stream.read(raf, start + done, out, done, length - done)
            check(n > 0) { "unexpected end at ${start + done}" }
            // Never hands out bytes past the piece it waited for.
            check(n <= stream.bytesLeftInPiece(start + done))
            done += n
        }
        return out
    }

    private fun session(port: Int): SessionManager {
        val settings = SettingsPack().listenInterfaces("127.0.0.1:$port")
        settings.setEnableDht(false)
        settings.setEnableLsd(false)
        settings.setBoolean(settings_pack.bool_types.allow_multiple_connections_per_ip.swigValue(), true)
        return SessionManager(false).also {
            // The phone's setup (posix disk I/O), so these tests cover the same storage path.
            it.start(TorrentEngine.sessionParams(settings))
            sessions += it
        }
    }

    private fun awaitHandle(s: SessionManager, info: TorrentInfo): TorrentHandle {
        var handle: TorrentHandle? = null
        waitUntil(10_000) {
            handle = s.find(info.infoHash())?.takeIf { it.isValid }
            handle != null
        }
        return handle!!
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            Thread.sleep(50)
        }
    }

    private companion object {
        @Volatile var loaded = false

        /** The desktop jar ships the .so as a resource; libtorrent4j loads it from a path property. */
        fun loadNativeLibrary() {
            if (loaded) return
            val lib = File.createTempFile("libtorrent4j", ".so").apply { deleteOnExit() }
            val resource = TorrentStreamTest::class.java.classLoader!!.getResourceAsStream("lib/x86_64/libtorrent4j.so")
                ?: error("libtorrent4j-linux not on the test classpath")
            resource.use { input -> lib.outputStream().use { input.copyTo(it) } }
            System.setProperty("libtorrent4j.jni.path", lib.absolutePath)
            loaded = true
        }
    }
}
