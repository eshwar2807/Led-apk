package com.eshwar.reelplay.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderRecoveryTest {
    @Test
    fun describesTheTrackThatFailed() {
        val dts = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_DTS).build()
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setWidth(3840).setHeight(2160).build()
        assertEquals("audio (DTS)", DecoderRecovery.describe(dts, C.TRACK_TYPE_AUDIO))
        assertEquals("video (HEVC 3840×2160)", DecoderRecovery.describe(hevc, C.TRACK_TYPE_VIDEO))
        assertEquals("audio", DecoderRecovery.describe(null, C.TRACK_TYPE_AUDIO))
        assertEquals("Dolby TrueHD", DecoderRecovery.codecName(MimeTypes.AUDIO_TRUEHD))
        assertEquals("X-UNKNOWN", DecoderRecovery.codecName("audio/x-unknown"))
    }
}
