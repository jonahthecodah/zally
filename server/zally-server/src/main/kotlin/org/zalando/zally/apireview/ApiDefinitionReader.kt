package org.zalando.zally.apireview

import org.apache.http.HttpHeaders.ACCEPT
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.CloseableHttpClient
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.BAD_GATEWAY
import org.springframework.http.HttpStatus.BAD_REQUEST
import org.springframework.http.HttpStatus.NOT_FOUND
import org.springframework.http.HttpStatus.PAYLOAD_TOO_LARGE
import org.springframework.http.HttpStatus.UNSUPPORTED_MEDIA_TYPE
import org.springframework.http.InvalidMediaTypeException
import org.springframework.http.MediaType
import org.springframework.http.MediaType.parseMediaTypes
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.stereotype.Component
import org.springframework.web.util.DefaultUriBuilderFactory
import org.zalando.zally.dto.ApiDefinitionRequest
import org.zalando.zally.exception.InaccessibleResourceUrlException
import org.zalando.zally.exception.MissingApiDefinitionException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.UnknownHostException

@Component
class ApiDefinitionReader(
    private val client: CloseableHttpClient,
    private val urlPolicy: ApiDefinitionUrlPolicy,
    private val properties: ApiDefinitionUrlProperties
) {

    fun read(request: ApiDefinitionRequest): String =
        request.apiDefinitionString?.let { it }
            ?: request.apiDefinition?.let { it }
            ?: request.apiDefinitionUrl?.let { readFromUrl(it) }
            ?: throw MissingApiDefinitionException()

    private fun readFromUrl(url: String): String? {
        val uri = parseUri(removeSpecialCharactersSuffix(url))
        return try {
            urlPolicy.checkUrl(uri)
            val request = HttpGet(uri).apply { setHeader(ACCEPT, MediaType.toString(MEDIA_TYPE_WHITELIST)) }
            client.execute(request).use { response ->
                try {
                    extractBody(response)
                } catch (exception: Exception) {
                    // abort instead of draining the rest of a rejected response
                    request.abort()
                    throw exception
                }
            }
        } catch (exception: ForbiddenApiDefinitionUrlException) {
            throw forbidden(exception)
        } catch (exception: UnknownHostException) {
            throw InaccessibleResourceUrlException("Unknown host while retrieving api definition url: ${uri.host}", NOT_FOUND)
        } catch (exception: IOException) {
            throw InaccessibleResourceUrlException("Unable to connect while retrieving api definition url", NOT_FOUND)
        }
    }

    private fun parseUri(url: String): URI = try {
        URI_FACTORY.expand(url)
    } catch (exception: IllegalArgumentException) {
        throw InaccessibleResourceUrlException("Invalid api definition url", BAD_REQUEST)
    }

    private fun extractBody(response: HttpResponse): String? {
        val statusCode = response.statusLine.statusCode
        if (statusCode !in 200..299) {
            val status = HttpStatus.resolve(statusCode)?.takeIf { it.is4xxClientError } ?: BAD_GATEWAY
            throw InaccessibleResourceUrlException("Received HTTP $statusCode while retrieving api definition url", status)
        }

        val headers = HttpHeaders()
        response.allHeaders.forEach { headers.add(it.name, it.value) }
        val contentType = try {
            headers.contentType
        } catch (exception: InvalidMediaTypeException) {
            throw unsupportedContentType(headers.getFirst(HttpHeaders.CONTENT_TYPE))
        }
        if (contentType != null && MEDIA_TYPE_WHITELIST.none { contentType.isCompatibleWith(it) }) {
            throw unsupportedContentType(contentType.toString())
        }

        val body = response.entity?.content?.let(::readLimited)
        if (body == null || body.isEmpty()) return null
        return StringHttpMessageConverter().read(
            String::class.java,
            object : HttpInputMessage {
                override fun getHeaders(): HttpHeaders = headers
                override fun getBody(): InputStream = ByteArrayInputStream(body)
            }
        )
    }

    private fun readLimited(input: InputStream): ByteArray {
        val limit = properties.maxResponseSize.toBytes()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > limit) {
                throw InaccessibleResourceUrlException(
                    "Api definition retrieved from url exceeds the maximum size of $limit bytes",
                    PAYLOAD_TOO_LARGE
                )
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun unsupportedContentType(contentType: String?) = InaccessibleResourceUrlException(
        "Unexpected content type while retrieving api definition url: $contentType",
        UNSUPPORTED_MEDIA_TYPE
    )

    private fun forbidden(exception: ForbiddenApiDefinitionUrlException) =
        InaccessibleResourceUrlException("Api definition url is not allowed: ${exception.message}", BAD_REQUEST)

    private fun removeSpecialCharactersSuffix(url: String): String = when {
        url.endsWith(SPECIAL_CHARACTERS_SUFFIX) -> url.substring(0, url.length - SPECIAL_CHARACTERS_SUFFIX.length)
        else -> url
    }

    companion object {

        // some internal systems add these characters at the end of some urls, don't know why
        private const val SPECIAL_CHARACTERS_SUFFIX = "%3D%3D"

        private val URI_FACTORY = DefaultUriBuilderFactory().apply {
            encodingMode = DefaultUriBuilderFactory.EncodingMode.URI_COMPONENT
        }

        // a whitelist of mime-types to accept when expecting JSON or YAML
        private val MEDIA_TYPE_WHITELIST = parseMediaTypes(
            listOf(
                // standard YAML mime-type plus variants
                "application/yaml",
                "application/x-yaml",
                "application/vnd.yaml",
                "text/yaml",
                "text/x-yaml",
                "text/vnd.yaml",

                // standard JSON mime-type plus variants
                "application/json",
                "application/javascript",
                "text/javascript",
                "text/x-javascript",
                "text/x-json",

                // github.com raw content pages issue text/plain content type for YAML
                "text/plain"
            )
        )
    }
}
