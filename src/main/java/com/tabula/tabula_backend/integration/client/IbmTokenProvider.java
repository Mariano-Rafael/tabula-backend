package com.tabula.tabula_backend.integration.client;

import com.tabula.tabula_backend.integration.service.IntegrationException;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class IbmTokenProvider {
    private final IbmHttpTransport http;
    private String token;
    private Instant expiresAt = Instant.EPOCH;
    public IbmTokenProvider(IbmHttpTransport http) { this.http = http; }

    public synchronized String token() {
        Instant now = Instant.now();
        if (token != null && now.isBefore(expiresAt)) { return token; }
        var response = http.authenticate();
        Object value = response.get("access_token");
        Object expiry = response.get("expiration");
        if (!(value instanceof String text) || text.isBlank() || text.length() > 16384
                || text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0 || !(expiry instanceof Number number)) { throw invalid(); }
        long ttl = number.longValue() - now.getEpochSecond();
        if (ttl <= 60) { throw invalid(); }
        token = text;
        expiresAt = now.plusSeconds(Math.min(ttl, 3600) - 60);
        return token;
    }
    public synchronized void invalidate() { token = null; expiresAt = Instant.EPOCH; }
    private static IntegrationException invalid() {
        return new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_INVALID_TOKEN_RESPONSE", "A IBM não retornou um token válido.");
    }
}
