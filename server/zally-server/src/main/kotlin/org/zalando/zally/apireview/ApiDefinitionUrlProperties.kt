package org.zalando.zally.apireview

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.util.unit.DataSize
import java.time.Duration

/**
 * Restrictions applied when the server fetches an API definition from a caller supplied `apiDefinitionUrl`.
 */
@ConfigurationProperties("zally.api-definition-url")
class ApiDefinitionUrlProperties {

    /**
     * Hosts that API definitions may be fetched from. An entry starting with `*.` also matches all subdomains.
     * When empty, any host resolving to a public address is allowed.
     */
    var allowedHosts: List<String> = emptyList()

    /**
     * Allows fetching from loopback, link-local, private and other non-public addresses. Only enable this when
     * the server is not reachable by untrusted callers.
     */
    var allowPrivateNetwork: Boolean = false

    var connectTimeout: Duration = Duration.ofSeconds(5)

    var readTimeout: Duration = Duration.ofSeconds(10)

    var maxRedirects: Int = 5

    var maxResponseSize: DataSize = DataSize.ofMegabytes(10)
}
