package dev.nostrobe

import java.net.InetAddress

/**
 * Cleartext HTTP is for the local network only: HTTPS goes anywhere, HTTP only to a local address or name.
 *
 * It is the app that enforces this, not the platform: a network security config lists hosts, it cannot say "any
 * private address". A config that listed three addresses blocked the LAN offload for every other server, the default
 * suggestion included ("Cleartext HTTP traffic to 192.168.1.20 not permitted", on a release build; a debug build,
 * which allows everything, hid it). So the platform allows cleartext and this refuses it towards the Internet.
 */
object LanPolicy {
    /** True when [url] (with or without a scheme) may be used. */
    fun allowed(url: String): Boolean {
        val s = url.trim()
        val scheme = if ("://" in s) s.substringBefore("://").lowercase() else "http"
        if (scheme == "https") return true
        if (scheme != "http") return false
        val host = hostOf(s) ?: return false
        return isLocalHost(host)
    }

    /** The host of [url], without the scheme, credentials, port, path or the brackets of an IPv6 literal. */
    fun hostOf(url: String): String? {
        var s = url.trim()
        if ("://" in s) s = s.substringAfter("://")
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if ('@' in s) s = s.substringAfterLast('@')
        val host = if (s.startsWith("[")) {
            s.substringAfter('[').substringBefore(']')
        } else {
            // An IPv6 literal needs its brackets in a URL: several colons without them is not an address.
            if (s.count { it == ':' } > 1) return null
            s.substringBefore(':')
        }
        return host.takeIf { it.isNotBlank() }
    }

    /** A name or an address of this machine or of a private network (RFC 1918, link-local, CGNAT, ULA). */
    fun isLocalHost(host: String): Boolean {
        val h = host.trim().lowercase().trimEnd('.')
        if (h.isEmpty()) return false
        if (':' in h) return isLocalIpv6(h)
        ipv4(h)?.let { (a, b) ->
            return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
                (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        // A name: a single label (the router, a hostname on the LAN) or a suffix reserved for local use.
        if ('.' !in h) return true
        return h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa") || h.endsWith(".internal")
    }

    /** The first two bytes of a dotted IPv4 literal, or null when [h] is not one (a name such as 192.168.1.1.example.com). */
    private fun ipv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { it in 0..255 && p.length <= 3 } ?: return null }
        return n[0] to n[1]
    }

    private fun isLocalIpv6(h: String): Boolean = try {
        val b = InetAddress.getByName(h).address
        b.size == 16 && (
            h == "::1" ||
                (b[0].toInt() and 0xFE) == 0xFC ||                                   // fc00::/7 unique local
                (b[0].toInt() and 0xFF) == 0xFE && (b[1].toInt() and 0xC0) == 0x80   // fe80::/10 link-local
            )
    } catch (_: Exception) {
        false
    }
}
