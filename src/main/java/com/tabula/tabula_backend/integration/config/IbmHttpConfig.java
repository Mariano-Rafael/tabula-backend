package com.tabula.tabula_backend.integration.config;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class IbmHttpConfig {
    @Bean("ibmRestClient")
    RestClient ibmRestClient(IbmSettings settings) {
        if (settings.connectTimeoutSeconds() < 1 || settings.connectTimeoutSeconds() > 60
                || settings.readTimeoutSeconds() < 1 || settings.readTimeoutSeconds() > 120
                || settings.maxConcurrentProbes() < 1 || settings.maxConcurrentProbes() > 20) {
            throw new IllegalStateException("Configuração inválida de limites HTTP IBM.");
        }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(settings.readTimeoutSeconds()));
        return RestClient.builder().requestFactory(factory).build();
    }
}
