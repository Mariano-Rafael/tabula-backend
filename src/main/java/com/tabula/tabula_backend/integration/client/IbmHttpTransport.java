package com.tabula.tabula_backend.integration.client;

import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.service.IntegrationException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.ObjectMapper;

@Component
public class IbmHttpTransport {
    private static final int MAX_BYTES = 1024 * 1024;
    private final RestClient http;
    private final ObjectMapper json;
    private final IbmSettings settings;
    public IbmHttpTransport(@Qualifier("ibmRestClient") RestClient http, ObjectMapper json, IbmSettings settings) {
        this.http = http; this.json = json; this.settings = settings;
    }

    public Map<String, Object> authenticate() {
        if (settings.apiKey() == null || settings.apiKey().isBlank()) {
            throw new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "IBM_CREDENTIAL_MISSING", "Credencial IBM não configurada.");
        }
        // Validate destination before sending even an IAM request.
        IbmEndpointPolicy.base(settings.serviceUrl());
        String body = "grant_type=" + URLEncoder.encode("urn:ibm:params:oauth:grant-type:apikey", StandardCharsets.UTF_8)
                + "&apikey=" + URLEncoder.encode(settings.apiKey(), StandardCharsets.UTF_8);
        return exchange(http.post().uri(URI.create("https://iam.cloud.ibm.com/identity/token"))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(body));
    }

    public Map<String, Object> get(String path, String version, String token, boolean probeList) {
        var uri = UriComponentsBuilder.fromUri(IbmEndpointPolicy.base(settings.serviceUrl())).path(path).queryParam("version", version);
        if (probeList) { uri.queryParam("page_limit", 1); }
        return exchange(http.get().uri(uri.build().encode().toUri()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> exchange(RestClient.RequestHeadersSpec<?> request) {
        try {
            return request.accept(MediaType.APPLICATION_JSON).exchange((req, response) -> {
                int status = response.getStatusCode().value();
                byte[] body = response.getBody().readNBytes(MAX_BYTES + 1);
                if (status < 200 || status >= 300) { throw upstream(status, body); }
                if (body.length > MAX_BYTES) { throw failure("IBM_RESPONSE_TOO_LARGE"); }
                try {
                    Object parsed = json.readValue(body, Object.class);
                    if (!(parsed instanceof Map<?, ?>)) { throw failure("IBM_INVALID_RESPONSE"); }
                    return (Map<String, Object>) parsed;
                } catch (IntegrationException ex) { throw ex; }
                catch (RuntimeException ex) { throw failure("IBM_INVALID_RESPONSE"); }
            });
        } catch (IntegrationException ex) { throw ex; }
        catch (ResourceAccessException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException) {
                throw new IntegrationException(HttpStatus.GATEWAY_TIMEOUT, "IBM_TIMEOUT", "Tempo limite ao consultar a IBM.");
            }
            throw new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "IBM_UNAVAILABLE", "Não foi possível conectar à IBM.");
        } catch (RestClientException ex) { throw failure("IBM_INVALID_RESPONSE"); }
    }

    private static IntegrationException failure(String code) { return new IntegrationException(HttpStatus.BAD_GATEWAY, code, "Resposta IBM inválida ou acima do limite permitido."); }
    private IntegrationException upstream(int status, byte[] body) {
        // Recognize a verified service limitation without forwarding arbitrary upstream messages.
        if (status == 400 && body.length <= MAX_BYTES) {
            try {
                Object parsed = json.readValue(body, Object.class);
                if (parsed instanceof Map<?, ?> error && "API is not supported on lite plan".equals(error.get("error"))) {
                    return new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_OPERATION_NOT_SUPPORTED", "Operação indisponível no plano IBM deste destino.");
                }
            } catch (RuntimeException ignored) { /* Generic classification below. */ }
        }
        String code = switch (status) {
            case 400, 422 -> "IBM_REQUEST_REJECTED";
            case 401 -> "IBM_AUTHENTICATION_FAILED";
            case 403 -> "IBM_PERMISSION_DENIED";
            case 404 -> "IBM_RESOURCE_NOT_FOUND";
            case 429 -> "IBM_RATE_LIMITED";
            default -> status >= 300 && status < 400 ? "IBM_REDIRECT_REJECTED" : "IBM_UPSTREAM_ERROR";
        };
        return new IntegrationException(status == 429 || status >= 500 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_GATEWAY,
                code, "A IBM não confirmou acesso ao recurso solicitado.");
    }
}
