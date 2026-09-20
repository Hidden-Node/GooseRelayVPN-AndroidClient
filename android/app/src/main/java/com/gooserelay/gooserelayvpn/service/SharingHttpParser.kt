package com.gooserelay.gooserelayvpn.service

/**
 * Parsed proxy-target from a request line.
 * For CONNECT: "host:port" (port optional, default 443 per convention here).
 * For absolute-form GET/POST: full URL is decomposed to host/port/path.
 */
internal data class ProxyTarget(val host: String, val port: Int, val path: String)

/**
 * Parses the target of a proxy request line.
 * - CONNECT: accepts "host:port", "host" (default port 443), and
 *   "[ipv6-literal]:port" / "[ipv6-literal]".
 * - GET/POST with absolute-form URL: "http://host[:port]/path".
 * Returns null when the line is not one of these shapes.
 */
internal fun parseProxyTarget(method: String, target: String): ProxyTarget? {
    if (method.equals("CONNECT", ignoreCase = true)) {
        if (target.startsWith("[")) {
            val close = target.indexOf(']')
            if (close < 0) return null
            val host = target.substring(1, close)
            if (host.isBlank()) return null
            val rest = target.substring(close + 1)
            val port = if (rest.isEmpty()) 443 else if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else return null
            return ProxyTarget(host, port, "")
        }
        val idx = target.lastIndexOf(':')
        if (idx <= 0) return if (target.isBlank() || target.contains(':')) null else ProxyTarget(target, 443, "")
        val host = target.substring(0, idx)
        val port = target.substring(idx + 1).toIntOrNull() ?: return null
        if (host.isBlank() || host.contains(':')) return null
        return ProxyTarget(host, port, "")
    }
    // absolute-form: scheme://host[:port]/path — only http accepted; https
    // always arrives as CONNECT.
    if (!method.equals("GET", ignoreCase = true) && !method.equals("POST", ignoreCase = true) &&
        !method.equals("HEAD", ignoreCase = true)) return null
    val m = Regex("^http://(\\[[0-9a-fA-F:.]+\\]|[^/:\\[\\]?]+)(?::(\\d+))?(/.*|\\?.*)?$", RegexOption.IGNORE_CASE).find(target) ?: return null
    val rawHost = m.groupValues[1]
    if (rawHost.isBlank()) return null
    val host = if (rawHost.startsWith("[") && rawHost.endsWith("]")) rawHost.substring(1, rawHost.length - 1) else rawHost
    if (host.isBlank()) return null
    val port = m.groupValues[2].toIntOrNull() ?: 80
    val rawPath = m.groupValues[3].ifEmpty { "/" }
    val path = if (rawPath.startsWith("/")) rawPath else "/$rawPath"
    return ProxyTarget(host, port, path)
}
