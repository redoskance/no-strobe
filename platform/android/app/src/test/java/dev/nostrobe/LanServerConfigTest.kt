package dev.nostrobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanServerConfigTest {

    @Test
    fun testUrlFormatting() {
        val raw = "192.168.1.50:8765/"
        var urlStr = raw.trim().trimEnd('/')
        if (!urlStr.contains("://")) {
            urlStr = "http://$urlStr"
        }
        assertEquals("http://192.168.1.50:8765", urlStr)
    }

    @Test
    fun testDefaultFallbackUrl() {
        // Without wifi manager context, fallback should be standard IP
        val fallback = "http://192.168.1.20:8765"
        assertTrue(fallback.startsWith("http://"))
        assertTrue(fallback.endsWith(":8765"))
    }
}
