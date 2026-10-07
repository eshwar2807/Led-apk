package com.eshwar.reelplay.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactorTest {
    @Test
    fun ownerNamesComeFromDeviceNames_butNotFromBareModels() {
        assertEquals(setOf("Eshwar's Tab S11 Ultra", "Eshwar"), Redactor.termsFromDeviceNames(listOf("Eshwar's Tab S11 Ultra"), "SM-X930"))
        assertEquals(setOf("Eshwar’s Buds", "Eshwar"), Redactor.termsFromDeviceNames(listOf("Eshwar’s Buds"), "SM-X930"))
        assertEquals(setOf("Tablette de Marie", "Marie"), Redactor.termsFromDeviceNames(listOf("Tablette de Marie"), "SM-X930"))
        assertEquals(emptySet<String>(), Redactor.termsFromDeviceNames(listOf("Galaxy Tab S11 Ultra", "SM-X930"), "SM-X930"))
        assertEquals(setOf("Living room TV"), Redactor.termsFromDeviceNames(listOf("Living room TV"), "SM-X930"))
    }

    @Test
    fun removesNamesAccountsAddressesAndThePackageName() {
        val text = """
            at com.example.player.PlayerActivity.onCreate(PlayerActivity.kt:120)
            Bluetooth: connected to Eshwar's Buds (A4:C3:F0:85:AC:2D) for ESHWAR
            account eshwar.n@gmail.com, peer 192.168.1.23:6881
            media3 1.11.1, libtorrent 2.1.0.39, ReelPlay 1.12
        """.trimIndent()
        val out = Redactor.redact(text, setOf("Eshwar's Buds", "Eshwar"), packageName = "com.example.player")
        assertTrue(out, out.contains("at app.PlayerActivity.onCreate"))
        assertTrue(out, out.contains("connected to [name] ([mac]) for [name]"))
        assertTrue(out, out.contains("account [email], peer [ip]:6881"))
        assertTrue(out, out.contains("media3 1.11.1, libtorrent 2.1.0.39, ReelPlay 1.12")) // Versions untouched.
        assertFalse(out, out.contains("shwar", ignoreCase = true))
    }
}
