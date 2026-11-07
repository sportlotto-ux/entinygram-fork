package desu.inugram.helpers.network

object VpnConfigParser {
    const val PROTOCOL_NONE = 0
    const val PROTOCOL_AWG = 1
    const val PROTOCOL_XRAY = 2

    data class Parsed(
        val protocol: Int,
        val raw: String,
        val endpointHost: String? = null,
        val endpointPort: Int? = null,
        val username: String? = null,
    )

    fun parse(text: String): Parsed? {
        val t = text.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("vless://", ignoreCase = true)) return parseVless(t)
        if (t.startsWith("{")) {
            if (containsProtocol(t, "amnezia_wg")) return Parsed(PROTOCOL_AWG, t)
            if (containsProtocol(t, "vless") || containsProtocol(t, "xray")) return Parsed(PROTOCOL_XRAY, t)
            return null
        }
        if (t.contains("[Interface]") && t.contains("[Peer]")) return parseAwg(t)
        return null
    }

    private fun containsProtocol(text: String, needle: String): Boolean =
        text.lowercase().contains(needle)

    fun parseAwg(text: String): Parsed? {
        val endpoint = extractValue(text, "Endpoint") ?: return null
        val (host, port) = splitHostPort(endpoint) ?: return null
        if (host.isEmpty()) return null
        return Parsed(PROTOCOL_AWG, text.trim(), host, port)
    }

    fun parseVless(text: String): Parsed? {
        val sep = text.indexOf('@')
        if (sep < 0) return null
        val after = text.substring(sep + 1)
        val end = after.indexOfAny(charArrayOf('/', '?', '#', ':')).let { if (it < 0) after.length else it }
        val colon = after.indexOf(':')
        val host: String
        val port: Int?
        if (colon >= 0 && colon < end) {
            host = after.substring(0, colon)
            port = after.substring(colon + 1, end).toIntOrNull()
        } else {
            host = after.substring(0, end)
            port = null
        }
        if (host.isEmpty()) return null
        return Parsed(PROTOCOL_XRAY, text.trim(), host, port)
    }

    private fun extractValue(text: String, key: String): String? {
        for (line in text.lineSequence()) {
            val l = line.trim()
            if (l.startsWith("$key", ignoreCase = true)) {
                val eq = l.indexOf('=')
                if (eq >= 0) return l.substring(eq + 1).trim()
            }
        }
        return null
    }

    private fun splitHostPort(endpoint: String): Pair<String, Int?>? {
        val e = endpoint.trim()
        return if (e.startsWith("[")) {
            val close = e.indexOf(']')
            if (close < 0) null else {
                val host = e.substring(1, close)
                val port = if (close + 1 < e.length && e[close + 1] == ':') e.substring(close + 2).toIntOrNull() else null
                host to port
            }
        } else {
            val colon = e.lastIndexOf(':')
            if (colon >= 0) {
                val port = e.substring(colon + 1).toIntOrNull()
                if (port != null) e.substring(0, colon) to port else e to null
            } else e to null
        }
    }
}
