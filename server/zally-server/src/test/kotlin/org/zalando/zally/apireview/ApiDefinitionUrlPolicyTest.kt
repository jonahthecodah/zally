package org.zalando.zally.apireview

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.net.InetAddress
import java.net.URI

class ApiDefinitionUrlPolicyTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.169.254", "172.16.0.1", "172.31.255.255",
            "192.0.0.1", "192.168.0.1", "198.18.0.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254", "fc00::1", "fd12:3456::1", "fe80::1",
            "fec0::1", "ff02::1", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::", "2001::1", "2001:db8::1"
        ]
    )
    fun `non-public addresses are detected`(address: String) {
        assertThat(ApiDefinitionUrlPolicy.isNonPublicAddress(InetAddress.getByName(address))).isTrue()
    }

    @ParameterizedTest
    @ValueSource(strings = ["8.8.8.8", "140.82.112.3", "172.32.0.1", "2606:4700::1111", "64:ff9b::808:808", "2002:808:808::"])
    fun `public addresses are allowed`(address: String) {
        assertThat(ApiDefinitionUrlPolicy.isNonPublicAddress(InetAddress.getByName(address))).isFalse()
    }

    @Test
    fun `resolver rejects hosts that resolve to an internal address on a later lookup`() {
        val answers = mutableListOf(
            arrayOf(InetAddress.getByName("140.82.112.3")),
            arrayOf(InetAddress.getByName("169.254.169.254"))
        )
        val policy = ApiDefinitionUrlPolicy(ApiDefinitionUrlProperties()) { answers.removeAt(0) }

        policy.checkUrl(URI("https://rebind.example.com/api.yaml"))
        assertThrows(ForbiddenApiDefinitionUrlException::class.java) { policy.resolve("rebind.example.com") }
    }

    @Test
    fun `resolver rejects hosts with any internal address`() {
        val policy = ApiDefinitionUrlPolicy(ApiDefinitionUrlProperties()) {
            arrayOf(InetAddress.getByName("140.82.112.3"), InetAddress.getByName("10.0.0.1"))
        }

        assertThrows(ForbiddenApiDefinitionUrlException::class.java) { policy.resolve("mixed.example.com") }
    }

    @Test
    fun `allow list matches exact hosts and wildcard subdomains`() {
        val policy = ApiDefinitionUrlPolicy(
            ApiDefinitionUrlProperties().apply { allowedHosts = listOf("raw.githubusercontent.com", "*.zalando.net") }
        )

        policy.checkLocation(URI("https://raw.githubusercontent.com/a.yaml"))
        policy.checkLocation(URI("https://api.team.zalando.net/a.yaml"))
        assertThrows(ForbiddenApiDefinitionUrlException::class.java) { policy.checkLocation(URI("https://zalando.net.evil.com/")) }
        assertThrows(ForbiddenApiDefinitionUrlException::class.java) { policy.checkLocation(URI("https://evilzalando.net/")) }
    }
}
