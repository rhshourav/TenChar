package com.rhshourav.tenchar

import java.net.URI
import java.net.URISyntaxException

/**
 * Rules for a relay address typed by the person. The app only talks to a
 * relay over HTTPS (cleartext is switched off in the manifest), so anything
 * else is refused here instead of failing later. Only a bare origin is
 * accepted: no credentials, path, query or fragment. Non-ASCII host names are
 * refused so that a look-alike domain cannot be typed in by mistake.
 *
 * The relay is untrusted by design (messages are end-to-end encrypted), but it
 * still sees IP addresses and timing, and both phones must use the same one.
 */
object RelayAddress {
    private const val MAX_INPUT = 255
    private const val MAX_HOST = 253
    private const val MAX_LABEL = 63

    /** The canonical form, "https://host" or "https://host:port", or null if not acceptable. */
    fun normalise(input: String): String? {
        var text = input.trim()
        if (text.isEmpty() || text.length > MAX_INPUT) return null
        if (text.any { it.isWhitespace() || it.isISOControl() }) return null
        if (!text.contains("://")) text = "https://$text"

        val uri = try {
            URI(text)
        } catch (e: URISyntaxException) {
            return null
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val path = uri.rawPath
        if (!path.isNullOrEmpty() && path != "/") return null

        val host = uri.host?.lowercase() ?: return null
        if (!isPlausibleHost(host)) return null
        val port = uri.port
        if (port != -1 && port !in 1..65535) return null

        return buildString {
            append("https://").append(host)
            if (port != -1 && port != 443) append(':').append(port)
        }
    }

    fun isValid(input: String): Boolean = normalise(input) != null

    private fun isPlausibleHost(host: String): Boolean {
        if (host.length > MAX_HOST) return false
        // A bracketed IPv6 literal was already checked by URI.
        if (host.startsWith("[")) return host.endsWith("]") && host.length > 2
        if (!host.contains('.')) return false
        return host.split('.').all { isPlausibleLabel(it) }
    }

    private fun isPlausibleLabel(label: String): Boolean =
        label.length in 1..MAX_LABEL &&
            !label.startsWith('-') && !label.endsWith('-') &&
            label.all { it in 'a'..'z' || it in '0'..'9' || it == '-' }
}