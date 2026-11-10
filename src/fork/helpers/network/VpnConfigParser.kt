package desu.inugram.helpers.network

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.zip.Inflater
import org.json.JSONObject

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
        val t = text.trim().trimStart('\uFEFF').trim()
        if (t.isEmpty()) return null
        if (t.startsWith("vpn://", ignoreCase = true)) return parseVpnLink(t.substring(6))
        if (t.startsWith("vless://", ignoreCase = true)) return parseVless(t)
        if (t.startsWith("{")) {
            if (t.contains("\"containers\"")) return parseAmnezia(t)
            return xrayToVless(t)?.let { parseVless(it) }
        }
        if (t.contains("[Interface]") && t.contains("[Peer]")) return parseAwg(t)
        return null
    }

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
        val end = after.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) after.length else it }
        val (host, port) = splitHostPort(after.substring(0, end)) ?: return null
        if (host.isEmpty()) return null
        return Parsed(PROTOCOL_XRAY, text.trim(), host, port)
    }

    // entiny: vpn:// links carry a base64 of zlib-compressed amnezia json
    private fun parseVpnLink(payload: String): Parsed? {
        var b64 = payload.trim().replace('-', '+').replace('_', '/')
        if (b64.length % 4 != 0) b64 += "=".repeat(4 - b64.length % 4)
        val bytes = try {
            Base64.decode(b64, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        val text = String(unzip(bytes) ?: bytes, Charsets.UTF_8).trim()
        return if (text.startsWith("{")) parseAmnezia(text) else null
    }

    private fun unzip(bytes: ByteArray): ByteArray? {
        // qCompress prepends a 4-byte length to the zlib stream
        val offsets = if (bytes.size > 4 && (bytes[0].toInt() and 0xFF) != 0x78) intArrayOf(4, 0) else intArrayOf(0, 4)
        for (offset in offsets) {
            inflateFrom(bytes, offset)?.let { return it }
        }
        return null
    }

    private fun inflateFrom(bytes: ByteArray, offset: Int): ByteArray? {
        if (offset >= bytes.size) return null
        return try {
            val inflater = Inflater()
            inflater.setInput(bytes, offset, bytes.size - offset)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) return null
                out.write(buf, 0, n)
            }
            inflater.end()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    private fun parseAmnezia(json: String): Parsed? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        val containers = root.optJSONArray("containers") ?: return null
        val found = LinkedHashMap<String, Parsed>()
        for (i in 0 until containers.length()) {
            val container = containers.optJSONObject(i) ?: continue
            val name = container.optString("container")
            val parsed = when {
                name.contains("awg") || name.contains("wireguard") -> {
                    val last = container.optJSONObject("awg")?.optString("last_config")
                        ?: container.optJSONObject("wireguard")?.optString("last_config")
                    decodeText(last)?.let { parseAwg(it) }
                }
                name.contains("xray") || name.contains("vless") -> {
                    decodeText(container.optJSONObject("xray")?.optString("last_config"))
                        ?.let { xrayToVless(it) }
                        ?.let { parseVless(it) }
                }
                else -> null
            }
            if (parsed != null) found[name] = parsed
        }
        if (found.isEmpty()) return null
        return found[root.optString("defaultContainer")] ?: found.values.first()
    }

    private fun decodeText(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        if (v.startsWith("{") || v.contains("[Interface]")) return v
        val bytes = try {
            Base64.decode(v, Base64.DEFAULT)
        } catch (_: Exception) {
            return null
        }
        return String(bytes, Charsets.UTF_8).trim().ifEmpty { null }
    }

    // entiny: the xray engine takes a vless:// uri, so an amnezia xray config has to be folded into one
    private fun xrayToVless(json: String): String? {
        val root = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }
        val outbounds = root.optJSONArray("outbounds") ?: return null
        for (i in 0 until outbounds.length()) {
            val outbound = outbounds.optJSONObject(i) ?: continue
            if (outbound.optString("protocol") != "vless") continue
            val vnext = outbound.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0) ?: continue
            val user = vnext.optJSONArray("users")?.optJSONObject(0) ?: continue
            val id = user.optString("id")
            val host = vnext.optString("address")
            val port = vnext.optInt("port")
            if (id.isEmpty() || host.isEmpty() || port <= 0) continue

            val stream = outbound.optJSONObject("streamSettings")
            val network = stream?.optString("network").orEmpty().ifEmpty { "tcp" }
            val security = stream?.optString("security").orEmpty().ifEmpty { "none" }
            val q = LinkedHashMap<String, String>()
            q["encryption"] = user.optString("encryption").ifEmpty { "none" }
            q["type"] = network
            q["security"] = security
            user.optString("flow").takeIf { it.isNotEmpty() }?.let { q["flow"] = it }
            if (security == "reality") {
                val reality = stream?.optJSONObject("realitySettings")
                q["sni"] = reality?.optString("serverName").orEmpty()
                q["fp"] = reality?.optString("fingerprint").orEmpty()
                q["pbk"] = reality?.optString("publicKey").orEmpty()
                reality?.optString("shortId").orEmpty().takeIf { it.isNotEmpty() }?.let { q["sid"] = it }
                reality?.optString("spiderX").orEmpty().takeIf { it.isNotEmpty() }?.let { q["spx"] = it }
            } else if (security == "tls") {
                val tls = stream?.optJSONObject("tlsSettings")
                q["sni"] = tls?.optString("serverName").orEmpty()
                q["fp"] = tls?.optString("fingerprint").orEmpty()
            }
            if (network == "ws") {
                val ws = stream?.optJSONObject("wsSettings")
                ws?.optString("path").orEmpty().takeIf { it.isNotEmpty() }?.let { q["path"] = it }
                ws?.optJSONObject("headers")?.optString("Host").orEmpty().takeIf { it.isNotEmpty() }?.let { q["host"] = it }
            } else if (network == "grpc") {
                stream?.optJSONObject("grpcSettings")?.optString("serviceName").orEmpty()
                    .takeIf { it.isNotEmpty() }?.let { q["serviceName"] = it }
            }

            val query = q.entries.filter { it.value.isNotEmpty() }.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
            return "vless://$id@$host:$port?$query"
        }
        return null
    }

    private fun enc(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (_: Exception) {
        value
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
