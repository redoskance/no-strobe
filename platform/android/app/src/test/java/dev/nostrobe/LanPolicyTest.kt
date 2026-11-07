package dev.nostrobe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPolicyTest {
    @Test
    fun cleartextIsAllowedToAnyPrivateAddress() {
        for (u in listOf(
            "http://192.168.1.20:8765", "http://192.168.69.15:8765", "192.168.0.7:8765", "http://10.0.2.2:8765",
            "http://10.20.30.40", "http://172.16.0.9:8765", "http://172.31.255.1", "http://127.0.0.1:8765",
            "http://169.254.10.10:8765", "http://100.64.1.1:8765", "http://100.127.255.254:8765",
        )) assertTrue(u, LanPolicy.allowed(u))
    }

    @Test
    fun cleartextIsRefusedTowardsTheInternet() {
        for (u in listOf(
            "http://8.8.8.8", "http://example.com:8765", "http://172.32.0.1", "http://172.15.0.1", "http://192.169.1.1",
            "http://100.128.0.1", "http://100.63.0.1", "http://11.0.0.1", "http://192.168.1.1.example.com",
            "http://user@evil.com/192.168.1.1", "ftp://192.168.1.20", "http://",
        )) assertFalse(u, LanPolicy.allowed(u))
    }

    @Test
    fun namesOfTheLocalNetworkAreAllowed() {
        for (u in listOf("http://mypc:8765", "http://localhost:8765", "http://nas.local:8765", "http://box.lan", "http://pc.home.arpa", "http://x.internal"))
            assertTrue(u, LanPolicy.allowed(u))
        assertFalse(LanPolicy.allowed("http://local.example.com"))
    }

    @Test
    fun httpsGoesAnywhere() {
        assertTrue(LanPolicy.allowed("https://example.com:8765"))
        assertTrue(LanPolicy.allowed("HTTPS://8.8.8.8"))
    }

    @Test
    fun ipv6LiteralsAreJudgedByTheirRange() {
        assertTrue(LanPolicy.allowed("http://[::1]:8765"))
        assertTrue(LanPolicy.allowed("http://[fe80::1]:8765"))
        assertTrue(LanPolicy.allowed("http://[fd12:3456::7]:8765"))
        assertFalse(LanPolicy.allowed("http://[2001:db8::1]:8765"))
        assertFalse("brackets are needed", LanPolicy.allowed("http://fe80::1"))
    }

    @Test
    fun theHostIsReadFromTheUrl() {
        assertEquals("192.168.1.20", LanPolicy.hostOf("http://192.168.1.20:8765/fix?x=1"))
        assertEquals("pc.local", LanPolicy.hostOf("pc.local"))
        assertEquals("evil.com", LanPolicy.hostOf("http://192.168.1.1@evil.com/"))
        assertEquals("fe80::1", LanPolicy.hostOf("http://[fe80::1]:8765"))
    }
}
