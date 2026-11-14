package org.zalando.zally.apireview

import net.jadler.Jadler.closeJadler
import net.jadler.Jadler.initJadlerUsing
import net.jadler.Jadler.onRequest
import net.jadler.Jadler.port
import net.jadler.Jadler.verifyThatRequest
import net.jadler.stubbing.server.jdk.JdkStubHttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType.APPLICATION_JSON_VALUE
import org.springframework.http.MediaType.TEXT_HTML_VALUE
import org.springframework.util.unit.DataSize
import org.zalando.zally.configuration.ApiDefinitionHttpClientConfiguration
import org.zalando.zally.dto.ApiDefinitionRequest
import org.zalando.zally.exception.InaccessibleResourceUrlException
import org.zalando.zally.exception.MissingApiDefinitionException
import org.zalando.zally.util.JadlerUtil

class ApiDefinitionReaderTest {

    private val contentInJson = "{\"swagger\":\"2.0\"}"

    private lateinit var reader: ApiDefinitionReader

    @BeforeEach
    fun setUp() {
        initJadlerUsing(JdkStubHttpServer())
        reader = reader(ApiDefinitionUrlProperties().apply { allowPrivateNetwork = true })
    }

    @AfterEach
    fun tearDown() {
        closeJadler()
    }

    @Test
    fun shouldThrowMissingApiDefinitionExceptionWhenDefinitionIsNotFound() {
        assertThrows(MissingApiDefinitionException::class.java, { reader.read(ApiDefinitionRequest()) })
    }

    @Test
    fun shouldReturnStringWhenApiDefinitionIsFound() {
        val request = ApiDefinitionRequest(contentInJson, null, "http://zalando.de")
        val result = reader.read(request)
        assertEquals(contentInJson, result)
    }

    @Test
    fun shouldReadJsonSwaggerDefinitionFromUrl() {
        val url = JadlerUtil.stubResource("test.json", contentInJson)
        val result = reader.read(ApiDefinitionRequest.fromUrl(url))
        assertEquals(contentInJson, result)
    }

    @Test
    fun shouldReadYamlSwaggerDefinitionFromUrl() {
        val contentInYaml = "swagger: \"2.0\""
        val url = JadlerUtil.stubResource("test.yaml", contentInYaml, APPLICATION_X_YAML_VALUE)
        val result = reader.read(ApiDefinitionRequest.fromUrl(url))

        assertEquals(contentInYaml, result)
    }

    @Test
    fun shouldPreferRawSpecification() {
        val rawYaml = "raw: yaml"
        val request = ApiDefinitionRequest("{\"some\": \"json\"", rawYaml, "http://zalando.de")
        val result = reader.read(request)
        assertEquals(rawYaml, result)
    }

    @Test
    fun shouldRetryLoadingOfUrlIfEndsWithSpecialEncodedCharacters() {
        val url = JadlerUtil.stubResource("test.json", contentInJson)
        val result = reader.read(ApiDefinitionRequest.fromUrl("$url%3D%3D"))
        assertEquals(contentInJson, result)
    }

    @Test
    fun shouldErrorBadRequestWhenDefinitionFromUrlUnsuccessful() {
        val url = JadlerUtil.stubResource("test.json", "", HttpStatus.UNAUTHORIZED.value(), APPLICATION_JSON_VALUE)

        assertThrows(InaccessibleResourceUrlException::class.java, { reader.read(ApiDefinitionRequest.fromUrl(url)) })
    }

    @Test
    fun shouldErrorBadRequestWhenDefinitionFromUrlWrongContentType() {
        val url = JadlerUtil.stubResource("test.json", "", HttpStatus.OK.value(), TEXT_HTML_VALUE)

        assertThrows(InaccessibleResourceUrlException::class.java, { reader.read(ApiDefinitionRequest.fromUrl(url)) })
    }

    @Test
    fun shouldRejectLoopbackUrlBeforeConnecting() {
        val url = "http://127.0.0.1:${port()}/health"
        onRequest().respond().withStatus(HttpStatus.OK.value()).withBody(contentInJson)

        assertForbidden(reader(ApiDefinitionUrlProperties()), url)
        assertForbidden(reader(ApiDefinitionUrlProperties()), "http://127.0.0.1:7979/health")
        verifyThatRequest().receivedNever()
    }

    @Test
    fun shouldRejectNonPublicAddresses() {
        val reader = reader(ApiDefinitionUrlProperties())
        listOf(
            "http://169.254.169.254/latest/meta-data/iam/security-credentials/zally-role",
            "http://localhost:7979/health",
            "http://0.0.0.0/",
            "http://10.0.0.1/",
            "http://192.168.1.1/",
            "http://[::1]/",
            "http://[::ffff:169.254.169.254]/",
            "http://[fd00::1]/",
            "http://2852039166/"
        ).forEach { assertForbidden(reader, it) }
    }

    @Test
    fun shouldRejectNonHttpSchemes() {
        listOf("file:///etc/passwd", "ftp://zalando.de/api.yaml", "jar:file:/tmp/x.jar!/api.yaml")
            .forEach { assertForbidden(reader, it) }
    }

    @Test
    fun shouldRejectHostsOutsideAllowList() {
        val reader = reader(ApiDefinitionUrlProperties().apply { allowedHosts = listOf("*.github.com") })
        assertForbidden(reader, "http://example.com/api.yaml")
        assertForbidden(reader, "http://notgithub.com/api.yaml")
    }

    @Test
    fun shouldFollowRedirectsToAllowedHosts() {
        val url = JadlerUtil.stubResource("test.json", contentInJson)
        stubRedirect("/redirect", url)

        val result = reader.read(ApiDefinitionRequest.fromUrl("http://localhost:${port()}/redirect"))

        assertEquals(contentInJson, result)
    }

    @Test
    fun shouldRejectRedirectToInternalAddress() {
        val reader = reader(
            ApiDefinitionUrlProperties().apply {
                allowPrivateNetwork = true
                allowedHosts = listOf("localhost")
            }
        )
        stubRedirect("/redirect", "http://169.254.169.254/latest/meta-data/")

        assertForbidden(reader, "http://localhost:${port()}/redirect")
    }

    @Test
    fun shouldRejectResponsesExceedingMaximumSize() {
        val reader = reader(
            ApiDefinitionUrlProperties().apply {
                allowPrivateNetwork = true
                maxResponseSize = DataSize.ofBytes(8)
            }
        )
        val url = JadlerUtil.stubResource("test.json", contentInJson)

        val exception = assertThrows(InaccessibleResourceUrlException::class.java) {
            reader.read(ApiDefinitionRequest.fromUrl(url))
        }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, exception.httpStatus)
    }

    @Test
    fun shouldNotEchoUpstreamErrorBody() {
        val url = JadlerUtil.stubResource("test.json", "secret", HttpStatus.FORBIDDEN.value(), APPLICATION_JSON_VALUE)

        val exception = assertThrows(InaccessibleResourceUrlException::class.java) {
            reader.read(ApiDefinitionRequest.fromUrl(url))
        }
        assertEquals(HttpStatus.FORBIDDEN, exception.httpStatus)
        assertEquals("Received HTTP 403 while retrieving api definition url", exception.message)
    }

    @Test
    fun shouldTreatEmptyResponseAsMissingDefinition() {
        val url = JadlerUtil.stubResource("empty.json", "")

        assertThrows(MissingApiDefinitionException::class.java) { reader.read(ApiDefinitionRequest.fromUrl(url)) }
    }

    @Test
    fun shouldNotAcceptRedirectBodyWhenRedirectsAreDisabled() {
        val reader = reader(
            ApiDefinitionUrlProperties().apply {
                allowPrivateNetwork = true
                maxRedirects = 0
            }
        )
        onRequest()
            .havingPathEqualTo("/redirect")
            .respond()
            .withStatus(HttpStatus.FOUND.value())
            .withHeader("Location", JadlerUtil.stubResource("test.json", contentInJson))
            .withHeader("Content-Type", APPLICATION_JSON_VALUE)
            .withBody(contentInJson)

        val exception = assertThrows(InaccessibleResourceUrlException::class.java) {
            reader.read(ApiDefinitionRequest.fromUrl("http://localhost:${port()}/redirect"))
        }
        assertEquals(HttpStatus.BAD_GATEWAY, exception.httpStatus)
    }

    @Test
    fun shouldNotEchoUpstreamServerErrorBody() {
        val url = JadlerUtil.stubResource("test.json", "x".repeat(100_000), HttpStatus.INTERNAL_SERVER_ERROR.value(), APPLICATION_JSON_VALUE)

        val exception = assertThrows(InaccessibleResourceUrlException::class.java) {
            reader.read(ApiDefinitionRequest.fromUrl(url))
        }
        assertEquals(HttpStatus.BAD_GATEWAY, exception.httpStatus)
        assertEquals("Received HTTP 500 while retrieving api definition url", exception.message)
    }

    private fun stubRedirect(path: String, location: String) {
        onRequest()
            .havingPathEqualTo(path)
            .respond()
            .withStatus(HttpStatus.FOUND.value())
            .withHeader("Location", location)
    }

    private fun assertForbidden(reader: ApiDefinitionReader, url: String) {
        val exception = assertThrows(InaccessibleResourceUrlException::class.java, { reader.read(ApiDefinitionRequest.fromUrl(url)) }, url)
        assertEquals(HttpStatus.BAD_REQUEST, exception.httpStatus, url)
    }

    private fun reader(properties: ApiDefinitionUrlProperties): ApiDefinitionReader {
        val policy = ApiDefinitionUrlPolicy(properties)
        return ApiDefinitionReader(ApiDefinitionHttpClientConfiguration().apiDefinitionHttpClient(policy, properties), policy, properties)
    }

    companion object {
        private const val APPLICATION_X_YAML_VALUE = "application/x-yaml"
    }
}
