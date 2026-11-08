package com.tabula.tabula_backend.integration.client;

import org.springframework.stereotype.Component;

@Component
public class IbmV2ReadClient {
    private final IbmHttpTransport http;

    public IbmV2ReadClient(IbmHttpTransport http) {
        this.http = http;
    }

    public void probe(String version, String token) {
        http.get("/v2/assistants", version, token, true);
    }

    public void environment(String assistant, String environment, String version, String token) {
        http.get("/v2/assistants/" + IbmEndpointPolicy.identifier(assistant) + "/environments/"
                + IbmEndpointPolicy.identifier(environment), version, token, false);
    }

    public void skill(String assistant, String skill, String version, String token) {
        http.get("/v2/assistants/" + IbmEndpointPolicy.identifier(assistant) + "/skills/"
                + IbmEndpointPolicy.identifier(skill), version, token, false);
    }
}
