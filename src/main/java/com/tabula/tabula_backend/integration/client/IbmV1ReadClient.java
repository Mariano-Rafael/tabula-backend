package com.tabula.tabula_backend.integration.client;

import org.springframework.stereotype.Component;

@Component
public class IbmV1ReadClient {
    private final IbmHttpTransport http;

    public IbmV1ReadClient(IbmHttpTransport http) {
        this.http = http;
    }

    public void probe(String version, String token) {
        http.get("/v1/workspaces", version, token, true);
    }

    public void workspace(String id, String version, String token) {
        http.get("/v1/workspaces/" + IbmEndpointPolicy.identifier(id), version, token, false);
    }
}
