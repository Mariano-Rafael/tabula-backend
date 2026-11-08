package com.tabula.tabula_backend.integration.client;

import com.tabula.tabula_backend.integration.service.IntegrationException;

import java.net.URI;

import org.springframework.http.HttpStatus;

public final class IbmEndpointPolicy {
    private IbmEndpointPolicy() {
    }

    public static URI base(String value) {
        try {
            URI uri = URI.create(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null
                    || !uri.getHost().matches("api\\.[a-z0-9-]+\\.assistant\\.watson\\.cloud\\.ibm\\.com")
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null
                    || !uri.getRawPath().matches("(?:/instances/[A-Za-z0-9-]+)?/?")) {
                throw new IllegalArgumentException();
            }
            return URI.create(value.replaceAll("/+$", ""));
        } catch (RuntimeException ex) {
            throw new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "IBM_CONFIGURATION_INVALID", "Configure uma URL pública HTTPS válida do Watson Assistant IBM Cloud.");
        }
    }

    public static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IntegrationException(HttpStatus.BAD_REQUEST, "INVALID_IBM_IDENTIFIER", "Identificador IBM inválido.");
        }
        return value;
    }
}
