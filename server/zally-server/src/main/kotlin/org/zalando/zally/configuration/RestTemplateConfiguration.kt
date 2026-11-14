package org.zalando.zally.configuration

import org.apache.http.HttpRequest
import org.apache.http.HttpResponse
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.client.DefaultRedirectStrategy
import org.apache.http.impl.client.HttpClients
import org.apache.http.protocol.HttpContext
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory
import org.springframework.web.client.RestTemplate
import org.zalando.zally.apireview.ApiDefinitionUrlPolicy
import org.zalando.zally.apireview.ApiDefinitionUrlProperties
import java.net.URI

@Configuration
@EnableConfigurationProperties(ApiDefinitionUrlProperties::class)
class RestTemplateConfiguration {

    @Bean
    fun apiDefinitionUrlPolicy(properties: ApiDefinitionUrlProperties): ApiDefinitionUrlPolicy =
        ApiDefinitionUrlPolicy(properties)

    @Bean
    fun restTemplate(policy: ApiDefinitionUrlPolicy, properties: ApiDefinitionUrlProperties): RestTemplate {
        val requestConfig = RequestConfig.custom()
            .setConnectTimeout(properties.connectTimeout.toMillis().toInt())
            .setConnectionRequestTimeout(properties.connectTimeout.toMillis().toInt())
            .setSocketTimeout(properties.readTimeout.toMillis().toInt())
            .setRedirectsEnabled(properties.maxRedirects > 0)
            .setMaxRedirects(properties.maxRedirects)
            .build()

        val httpClient = HttpClients.custom()
            .setDnsResolver(policy)
            .setRedirectStrategy(CheckedRedirectStrategy(policy))
            .setDefaultRequestConfig(requestConfig)
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableAuthCaching()
            .build()

        return RestTemplate(HttpComponentsClientHttpRequestFactory(httpClient))
    }

    private class CheckedRedirectStrategy(private val policy: ApiDefinitionUrlPolicy) : DefaultRedirectStrategy() {
        override fun getLocationURI(request: HttpRequest, response: HttpResponse, context: HttpContext): URI =
            super.getLocationURI(request, response, context).also(policy::checkLocation)
    }
}
