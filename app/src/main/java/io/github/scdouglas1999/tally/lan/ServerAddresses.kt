package io.github.scdouglas1999.tally.lan

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

/**
 * Server addresses as the router compares them: `scheme://host:port/path` with no trailing slash, query or fragment
 * (`http://192.168.1.50:9000`, `https://example.org/jellyfin`). Null when [url] is not an http(s) address.
 */
internal fun normalizeBase(url: String?): String? {
    val parsed = url?.trim()?.toHttpUrlOrNull() ?: return null
    return parsed
        .newBuilder()
        .query(null)
        .fragment(null)
        .build()
        .toString()
        .trimEnd('/')
}

/** A Jellyfin server id in one spelling: 32 lowercase hex digits, no dashes (the server writes it that way). */
internal fun normalizeServerId(id: String?): String? =
    id
        ?.trim()
        ?.replace("-", "")
        ?.lowercase()
        ?.takeIf { it.length == 32 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

internal fun normalizeServerId(id: UUID): String = id.toString().replace("-", "").lowercase()

/** True when [url] is [base] or under it (same scheme, host and port; the path starts with the base's path). */
internal fun isUnder(
    url: HttpUrl,
    base: HttpUrl,
): Boolean {
    if (url.scheme != base.scheme || !url.host.equals(base.host, ignoreCase = true) || url.port != base.port) {
        return false
    }
    val basePath = base.encodedPath.trimEnd('/')
    if (basePath.isEmpty()) return true
    val path = url.encodedPath
    return path == basePath || path.startsWith("$basePath/")
}

/** [url] (under [from]) moved to [to]: the part of the path after [from]'s path is kept, with the query. */
internal fun rebase(
    url: HttpUrl,
    from: HttpUrl,
    to: HttpUrl,
): HttpUrl {
    if (from == to) return url
    val rest = url.encodedPath.removePrefix(from.encodedPath.trimEnd('/'))
    val path = (to.encodedPath.trimEnd('/') + rest).ifEmpty { "/" }
    return url
        .newBuilder()
        .scheme(to.scheme)
        .host(to.host)
        .port(to.port)
        .encodedPath(path)
        .build()
}

/**
 * Whether [host] is on the home network by its name alone: private IPv4 ranges (10/8, 172.16/12, 192.168/16),
 * link-local, loopback, IPv6 unique-local and link-local, and names that only a home network resolves (`.local`,
 * `.lan`, `.home.arpa`, a name with no dot). Carrier-grade NAT and VPN ranges (100.64/10) are not home.
 */
internal fun isHomeHost(host: String): Boolean {
    val name = host.lowercase().removePrefix("[").removeSuffix("]")
    if (name == "localhost") return true
    ipv4(name)?.let { (a, b) ->
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
    if (':' in name) {
        return name == "::1" || name.startsWith("fc") || name.startsWith("fd") || name.startsWith("fe8") ||
            name.startsWith("fe9") || name.startsWith("fea") || name.startsWith("feb")
    }
    return '.' !in name || name.endsWith(".local") || name.endsWith(".lan") || name.endsWith(".home.arpa")
}

/** The first two octets of a dotted IPv4 literal, or null when [name] is not one. */
private fun ipv4(name: String): Pair<Int, Int>? {
    val parts = name.split('.')
    if (parts.size != 4) return null
    val octets = parts.map { part -> part.toIntOrNull()?.takeIf { part.isNotEmpty() && it in 0..255 } ?: return null }
    return octets[0] to octets[1]
}
