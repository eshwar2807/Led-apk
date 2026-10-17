package com.eshwar.reelplay.web

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

class HlsTest {

    private val base = "https://cdn.example.com/show/master.m3u8"

    @Test
    fun masterPlaylist_listsQualitiesBestFirst_withSeparateAudio() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",DEFAULT=NO,URI="audio/en_alt.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English main",DEFAULT=YES,URI="audio/en.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS="avc1.4d401e,mp4a.40.2"
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,AVERAGE-BANDWIDTH=4200000,RESOLUTION=1920x1080,AUDIO="aud"
            https://other.example.com/1080p.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=720x1280
            portrait/index.m3u8
        """.trimIndent()
        assertTrue(Hls.isMaster(text))
        val v = Hls.parseMaster(text, base)
        assertEquals(listOf("portrait", "1080p", "360p").size, v.size)
        assertEquals("https://other.example.com/1080p.m3u8", v[1].url)
        assertEquals(4_200_000L, v[1].bandwidth)
        assertEquals("https://cdn.example.com/show/audio/en.m3u8", v[1].audioUrl)
        assertEquals("1080p · 4.2 Mbps", v[1].label)
        assertEquals("720p · 2.5 Mbps", v[0].label) // Portrait counts by its short side.
        assertEquals("https://cdn.example.com/show/360p/index.m3u8", v[2].url)
        assertNull(v[2].audioUrl)
    }

    @Test
    fun mediaPlaylist_segmentsKeysRangesAndInit() {
        val text = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:7
            #EXT-X-MAP:URI="init.mp4",BYTERANGE="720@0"
            #EXTINF:6.0,
            #EXT-X-BYTERANGE:1000@720
            video.mp4
            #EXTINF:5.5,
            #EXT-X-BYTERANGE:500
            video.mp4
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/k1",IV=0x000102030405060708090a0b0c0d0e0f
            #EXTINF:4,
            seg3.m4s?token=a b
            #EXT-X-ENDLIST
        """.trimIndent()
        val p = Hls.parseMedia(text, base)
        assertTrue(p.ended)
        assertEquals("https://cdn.example.com/show/init.mp4", p.initUrl)
        assertEquals(0L..719L, p.initRange)
        assertEquals(3, p.segments.size)
        assertEquals(720L..1719L, p.segments[0].range)
        assertEquals(1720L..2219L, p.segments[1].range) // Follows the previous range.
        assertEquals(15.5, p.durationSec, 0.001)
        assertEquals(listOf(7L, 8L, 9L), p.segments.map { it.sequence })
        assertNull(p.segments[1].key)
        assertEquals("AES-128", p.segments[2].key?.method)
        assertEquals(15, p.segments[2].key?.iv?.get(15)?.toInt())
        assertEquals("https://cdn.example.com/show/seg3.m4s?token=a%20b", p.segments[2].url)
        assertEquals(setOf("AES-128"), p.encryptedWith)
    }

    private fun encrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray =
        Cipher.getInstance("AES/CBC/PKCS5Padding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(data)
        }

    @Test
    fun downloadsInOrder_decryptsAes_andResumesAfterAFailure(): Unit = runBlocking {
        val key = Random(1).nextBytes(16)
        val plain = (0 until 12).map { Random(it + 10).nextBytes(10_000 + it * 37) }
        val init = "INIT".toByteArray()
        val files = HashMap<String, ByteArray>()
        files["https://h/init.mp4"] = init
        files["https://h/key"] = key
        val sb = StringBuilder("#EXTM3U\n#EXT-X-MEDIA-SEQUENCE:100\n#EXT-X-MAP:URI=\"init.mp4\"\n")
        plain.forEachIndexed { i, bytes ->
            if (i == 6) sb.append("#EXT-X-KEY:METHOD=AES-128,URI=\"key\"\n") // Second half encrypted, implicit IV.
            sb.append("#EXTINF:2,\nseg$i.m4s\n")
            val iv = ByteBuffer.allocate(16).putLong(8, 100L + i).array()
            files["https://h/seg$i.m4s"] = if (i >= 6) encrypt(bytes, key, iv) else bytes
        }
        sb.append("#EXT-X-ENDLIST\n")
        val playlist = Hls.parseMedia(sb.toString(), "https://h/index.m3u8")

        var failOn: String? = "https://h/seg8.m4s"
        val fetches = HashMap<String, Int>()
        val fetcher = Fetcher { url, _ ->
            synchronized(fetches) { fetches[url] = (fetches[url] ?: 0) + 1 }
            if (url == failOn) throw IOException("connection reset")
            files[url] ?: throw IOException("404 $url")
        }
        val dir = Files.createTempDirectory("hls").toFile()
        val out = File(dir, "out.mp4")
        val state = File(dir, "state")

        // First attempt dies partway (seg8 keeps failing, retries run out).
        try {
            HlsDownloader(fetcher, parallel = 3, retries = 2).download(playlist, out, state) {}
            fail("should have failed")
        } catch (_: IOException) {
        }
        val doneBefore = state.readText().split(' ')[0].toInt()
        assertTrue("checkpoint after the pieces before seg8: $doneBefore", doneBefore in 1..9)

        // Second attempt resumes and doesn't refetch what was already written.
        failOn = null
        val firstFetchOfSeg0 = fetches["https://h/seg0.m4s"]
        var last: HlsDownloader.Progress? = null
        HlsDownloader(fetcher, parallel = 3).download(playlist, out, state) { last = it }
        assertEquals(firstFetchOfSeg0, fetches["https://h/seg0.m4s"])
        assertEquals(13, last!!.totalPieces)
        assertEquals(13, last!!.donePieces)
        val expected = init + plain.reduce { a, b -> a + b }
        assertArrayEquals(expected, out.readBytes())
        assertEquals(expected.size.toLong(), last!!.bytes)
        dir.deleteRecursively()
    }

    @Test
    fun refusesLiveAndDrmStreams(): Unit = runBlocking {
        val dir = Files.createTempDirectory("hls").toFile()
        val live = Hls.parseMedia("#EXTM3U\n#EXTINF:2,\na.ts\n", "https://h/i.m3u8")
        assertFalse(live.ended)
        val drm = Hls.parseMedia(
            "#EXTM3U\n#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"skd://x\",KEYFORMAT=\"com.apple.streamingkeydelivery\"\n" +
                "#EXTINF:2,\na.ts\n#EXT-X-ENDLIST\n",
            "https://h/i.m3u8",
        )
        for ((p, word) in listOf(live to "live", drm to "copy-protected")) {
            try {
                HlsDownloader({ _, _ -> ByteArray(0) }).download(p, File(dir, "o"), File(dir, "s")) {}
                fail("should refuse")
            } catch (e: UnsupportedStreamException) {
                assertTrue(e.message!!, e.message!!.contains(word))
            }
        }
        dir.deleteRecursively()
    }
}

class PackedAudioTest {
    @Test
    fun stripsLeadingId3TagsFromPackedAudioOnly() {
        val tagBody = ByteArray(300) { 7 }
        // ID3v2.4 header, size 300 as a syncsafe integer (2 * 128 + 44).
        val tag = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0, 0, 0, 2, 44) + tagBody
        val adts = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte(), 0x02, 0x1F, 0xFC.toByte())
        assertArrayEquals(adts, HlsDownloader.stripId3(tag + adts))
        assertArrayEquals(adts, HlsDownloader.stripId3(adts))
        assertTrue(HlsDownloader.isPackedAudio("https://h/a/seg1.aac?x=1"))
        assertFalse(HlsDownloader.isPackedAudio("https://h/a/seg1.ts"))
    }
}

class DownloadChoicesTest {
    private fun v(h: Int, bw: Long, codecs: String?) = Hls.Variant("https://h/$h-$bw-$codecs.m3u8", bw, h * 16 / 9, h, codecs, null)

    @Test
    fun onePerResolution_preferringH264() {
        val choices = Hls.downloadChoices(
            listOf(
                v(1080, 8_000_000, "hvc1.2.4.L123,mp4a.40.2"),
                v(1080, 6_000_000, "avc1.640028,mp4a.40.2"),
                v(1080, 4_700_000, "avc1.640028,mp4a.40.2"),
                v(720, 3_000_000, "hvc1.2.4.L93,mp4a.40.2"),
                v(720, 2_600_000, "avc1.4d401f,mp4a.40.2"),
                v(360, 800_000, "avc1.4d401e,mp4a.40.2"),
            ),
        )
        assertEquals(listOf(1080 to 6_000_000L, 720 to 2_600_000L, 360 to 800_000L), choices.map { it.height to it.bandwidth })
        // No codec info at all: just one per resolution.
        assertEquals(2, Hls.downloadChoices(listOf(v(720, 1, null), v(720, 2, null), v(480, 1, null))).size)
    }
}
