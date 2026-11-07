package dev.nostrobe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The HEVC choice reads the MIME types of the codecs, not their component names. */
class HevcEncoderTest {

    @Test
    fun encoderListingHevcMimeIsFound() {
        val codecs = listOf(true to arrayOf("video/avc"), true to arrayOf("video/hevc"))
        assertTrue(hasHevcEncoder(codecs))
    }

    @Test
    fun hevcDecoderIsNotAnEncoder() {
        assertFalse(hasHevcEncoder(listOf(false to arrayOf("video/hevc"))))
    }

    @Test
    fun deviceWithoutHevcEncoderIsFalse() {
        val codecs = listOf(true to arrayOf("video/avc"), false to arrayOf("video/hevc"))
        assertFalse(hasHevcEncoder(codecs))
    }

    @Test
    fun mimeMatchIgnoresCase() {
        assertTrue(hasHevcEncoder(listOf(true to arrayOf("video/HEVC"))))
    }
}
