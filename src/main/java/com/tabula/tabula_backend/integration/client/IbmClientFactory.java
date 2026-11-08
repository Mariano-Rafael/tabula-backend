package com.tabula.tabula_backend.integration.client;

import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.repository.IbmIntegrationRepository;
import com.tabula.tabula_backend.integration.service.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Explicit per-connection clients; no mutable request-global endpoint or thread-local credentials. */
@Component
public class IbmClientFactory {
    public record Clients(IbmHttpTransport http, IbmTokenProvider tokens) { }
    private record Cached(long revision, Clients clients) { }
    private final IbmIntegrationRepository integrations;
    private final IbmCredentialCipher cipher;
    private final RestClient rest;
    private final ObjectMapper json;
    private final Clients legacy;
    private final Map<String, Cached> cache = new LinkedHashMap<>(16, 0.75f, true);
    public IbmClientFactory(IbmIntegrationRepository integrations, IbmCredentialCipher cipher,
            @Qualifier("ibmRestClient") RestClient rest, ObjectMapper json, IbmHttpTransport http, IbmTokenProvider tokens) {
        this.integrations = integrations; this.cipher = cipher; this.rest = rest; this.json = json; this.legacy = new Clients(http, tokens);
    }
    public IbmConnectionRef reference(String integrationId) {
        var integration = integrations.findById(integrationId).orElseThrow(IbmClientFactory::missing);
        return "PROPERTIES".equals(integration.getCredentialSource()) ? null : new IbmConnectionRef(integrationId, integration.getServiceUrl());
    }
    public Clients clients(IbmConnectionRef ref) {
        if (ref == null) return legacy;
        var integration = integrations.findById(ref.integrationId()).orElseThrow(IbmClientFactory::missing);
        if (!"DATABASE".equals(integration.getCredentialSource()) || !Objects.equals(ref.serviceUrl(), integration.getServiceUrl())) throw missing();
        // Only cache creation is serialized. Remote HTTP and IAM locks are per connection.
        synchronized (cache) {
            var cached = cache.get(integration.getId());
            if (cached != null && cached.revision() == integration.getCredentialRevision()) return cached.clients();
            String key = cipher.decrypt(integration.getApiKeyCiphertext(), integration.credentialContext());
            var transport = new IbmHttpTransport(rest, json, IbmSettings.connection(integration.getServiceUrl(), key));
            var clients = new Clients(transport, new IbmTokenProvider(transport));
            cache.put(integration.getId(), new Cached(integration.getCredentialRevision(), clients));
            if (cache.size() > 256) cache.remove(cache.keySet().iterator().next());
            return clients;
        }
    }
    private static IntegrationException missing() { return new IntegrationException(HttpStatus.CONFLICT, "IBM_CONNECTION_UNAVAILABLE", "A conexão IBM original não está disponível ou foi alterada."); }
}
