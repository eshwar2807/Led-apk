package com.eshwar.reelplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The app side of update-server's latest.json (see update-server/main.go's Manifest). */
class AppUpdateTest {

    private val base = "https://reelplay-updates.fly.dev/reelplay/latest.json"

    @Test
    fun parsesManifest_andResolvesApkNextToIt() {
        val u = AppUpdate.parse(
            """{"versionCode":5,"versionName":"1.4","apk":"ReelPlay-5.apk","sha256":"ABCDEF",
               "size":27676501,"notes":"Faster torrents","publishedAt":"2026-10-03"}""",
            base,
        )
        assertEquals(5, u.versionCode)
        assertEquals("1.4", u.versionName)
        assertEquals("https://reelplay-updates.fly.dev/reelplay/ReelPlay-5.apk", u.apkUrl)
        assertEquals("abcdef", u.sha256) // Compared against our lowercase digest.
        assertEquals(27_676_501L, u.size)
        assertEquals("Faster torrents", u.notes)
    }

    @Test
    fun refusesApkNamesThatPointElsewhere() {
        for (bad in listOf("../evil.apk", "https://elsewhere.example/x.apk", "ReelPlay-5.zip")) {
            assertThrows(IllegalArgumentException::class.java) {
                AppUpdate.parse(
                    """{"versionCode":5,"versionName":"1.4","apk":"$bad","sha256":"aa","size":1}""",
                    base,
                )
            }
        }
    }

    @Test
    fun sha256MatchesTheServersFormat() {
        val f = Files.createTempFile("apk", ".bin").toFile()
        f.writeText("abc")
        // Well-known SHA-256 of "abc", lowercase hex like update-server writes.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updates.sha256(f))
        f.delete()
    }

    @Test
    fun explainsServerProblemsInPlainWords() {
        assertTrue(Updates.explain(Updates.HttpStatusException(404)).contains("no release has been published"))
        assertTrue(Updates.explain(Updates.HttpStatusException(503)).contains("HTTP 503"))
        assertTrue(Updates.explain(java.net.SocketTimeoutException()).contains("internet connection"))
    }
}
