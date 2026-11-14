package org.zalando.zally.apireview

import org.apache.http.conn.DnsResolver
import org.apache.http.impl.conn.SystemDefaultDnsResolver
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

class ForbiddenApiDefinitionUrlException(message: String) : IOException(message)

/**
 * Decides which urls the server may fetch API definitions from. It is also used as the [DnsResolver] of the
 * HTTP client, so every connection (including redirect hops) is checked against the address that is actually used.
 */
class ApiDefinitionUrlPolicy(
    private val properties: ApiDefinitionUrlProperties,
    private val delegate: DnsResolver = SystemDefaultDnsResolver.INSTANCE
) : DnsResolver {

    fun checkUrl(uri: URI) {
        checkLocation(uri)
        resolve(uri.host)
    }

    fun checkLocation(uri: URI) {
        if (uri.scheme?.lowercase() !in ALLOWED_SCHEMES) {
            throw ForbiddenApiDefinitionUrlException("only http and https urls are supported")
        }
        val host = uri.host?.lowercase()?.removeSuffix(".")
        if (host.isNullOrEmpty()) {
            throw ForbiddenApiDefinitionUrlException("url has no valid host")
        }
        if (properties.allowedHosts.isNotEmpty() && properties.allowedHosts.none { matchesHost(it, host) }) {
            throw ForbiddenApiDefinitionUrlException("host $host is not in the list of allowed hosts")
        }
    }

    override fun resolve(host: String): Array<InetAddress> {
        val addresses = delegate.resolve(host)
        if (!properties.allowPrivateNetwork && addresses.any(::isNonPublicAddress)) {
            throw ForbiddenApiDefinitionUrlException("host $host resolves to a non-public address")
        }
        return addresses
    }

    private fun matchesHost(pattern: String, host: String): Boolean {
        val normalized = pattern.trim().lowercase().removeSuffix(".")
        return if (normalized.startsWith("*.")) {
            host.endsWith(normalized.substring(1))
        } else {
            host == normalized
        }
    }

    companion object {
        private val ALLOWED_SCHEMES = setOf("http", "https")

        fun isNonPublicAddress(address: InetAddress): Boolean =
            address.isAnyLocalAddress ||
                address.isLoopbackAddress ||
                address.isLinkLocalAddress ||
                address.isSiteLocalAddress ||
                address.isMulticastAddress ||
                when (address) {
                    is Inet4Address -> isNonPublicIpv4(address.address.map { it.toInt() and 0xFF })
                    is Inet6Address -> isNonPublicIpv6(address.address.map { it.toInt() and 0xFF })
                    else -> true
                }

        private fun isNonPublicIpv4(b: List<Int>): Boolean = when {
            b[0] == 0 || b[0] == 10 || b[0] == 127 -> true
            b[0] == 100 && (b[1] and 0xC0) == 64 -> true // carrier-grade NAT 100.64.0.0/10
            b[0] == 169 && b[1] == 254 -> true
            b[0] == 172 && (b[1] and 0xF0) == 16 -> true
            b[0] == 192 && b[1] == 168 -> true
            b[0] == 192 && b[1] == 0 && (b[2] == 0 || b[2] == 2) -> true
            b[0] == 198 && (b[1] and 0xFE) == 18 -> true
            b[0] == 198 && b[1] == 51 && b[2] == 100 -> true
            b[0] == 203 && b[1] == 0 && b[2] == 113 -> true
            b[0] >= 224 -> true
            else -> false
        }

        private fun isNonPublicIpv6(b: List<Int>): Boolean = when {
            (b[0] and 0xFE) == 0xFC -> true // unique local fc00::/7
            b[0] == 0xFE && (b[1] and 0x80) == 0x80 -> true // link-local and site-local fe80::/9
            b[0] == 0xFF -> true
            b.subList(0, 10).all { it == 0 } && b[10] == 0xFF && b[11] == 0xFF -> isNonPublicIpv4(b.subList(12, 16))
            b.subList(0, 12).all { it == 0 } -> true // ::, ::1 and deprecated IPv4-compatible addresses
            b[0] == 0x00 && b[1] == 0x64 && b[2] == 0xFF && b[3] == 0x9B ->
                b.subList(4, 12).any { it != 0 } || isNonPublicIpv4(b.subList(12, 16)) // NAT64
            b[0] == 0x01 && b[1] == 0x00 && b.subList(2, 8).all { it == 0 } -> true // discard 100::/64
            b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x00 && b[3] == 0x00 -> true // Teredo 2001::/32
            b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0D && b[3] == 0xB8 -> true // documentation 2001:db8::/32
            b[0] == 0x20 && b[1] == 0x02 -> isNonPublicIpv4(b.subList(2, 6)) // 6to4
            else -> false
        }
    }
}
