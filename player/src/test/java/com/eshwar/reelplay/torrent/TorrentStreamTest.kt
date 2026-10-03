package com.eshwar.reelplay.torrent

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.TorrentStatus
import org.libtorrent4j.swig.settings_pack
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
            System.getProperty("os.name").startsWith("Linux") && System.getProperty("os.arch") in setOf("amd64", "x86_64"),
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
        val info = TorrentInfo(built.entry().bencode())
        val meta = TorrentMeta(info)
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
        val stream = TorrentStream(awaitHandle(leecher, info), meta, file, File(dir, file.path))
        stream.primeEnds()

        // Like a player: header first, then the tail (MP4 index), then seek into the middle.
        waitUntil(60_000) { stream.has(stream.firstPiece) }
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
            }
            assertEquals(-1, stream.read(raf, video.size.toLong(), ByteArray(16), 0, 16))
        }
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
            it.start(SessionParams(settings))
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
