package org.zalando.zally.configuration

import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.DefaultRedirectStrategy
import org.apache.http.impl.client.HttpClients
import org.apache.http.protocol.HttpContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.zalando.zally.apireview.ApiDefinitionUrlPolicy
import org.zalando.zally.apireview.ApiDefinitionUrlProperties
import java.net.URI

@Configuration
@EnableConfigurationProperties(ApiDefinitionUrlProperties::class)
class ApiDefinitionHttpClientConfiguration {

    @Bean
    fun apiDefinitionUrlPolicy(properties: ApiDefinitionUrlProperties): ApiDefinitionUrlPolicy =
        ApiDefinitionUrlPolicy(properties)

    @Bean
    fun apiDefinitionHttpClient(policy: ApiDefinitionUrlPolicy, properties: ApiDefinitionUrlProperties): CloseableHttpClient {
        val requestConfig = RequestConfig.custom()
            .setConnectTimeout(properties.connectTimeout.toMillis().toInt())
            .setConnectionRequestTimeout(properties.connectTimeout.toMillis().toInt())
            .setSocketTimeout(properties.readTimeout.toMillis().toInt())
            .setRedirectsEnabled(properties.maxRedirects > 0)
            .setMaxRedirects(properties.maxRedirects)
            .build()

        return HttpClients.custom()
            .setDnsResolver(policy)
            .setRedirectStrategy(CheckedRedirectStrategy(policy))
            .setDefaultRequestConfig(requestConfig)
            .setMaxConnTotal(MAX_CONNECTIONS)
            .setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
            .build()
    }

    private class CheckedRedirectStrategy(private val policy: ApiDefinitionUrlPolicy) : DefaultRedirectStrategy() {
        override fun getLocationURI(request: HttpRequest, response: HttpResponse, context: HttpContext): URI =
            super.getLocationURI(request, response, context).also(policy::checkLocation)
    }

    companion object {
        private const val MAX_CONNECTIONS = 100
        private const val MAX_CONNECTIONS_PER_ROUTE = 20
    }
}
