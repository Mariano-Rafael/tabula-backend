package com.tabula.tabula_backend.integration.model;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "ibm_integrations")
public class IbmIntegration {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "portfolio_id", nullable = false, length = 36)
    private String portfolioId;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "credential_source", nullable = false, length = 16)
    private String credentialSource = "PROPERTIES";
    @Column(name = "service_url", length = 512)
    private String serviceUrl;
    @Column(name = "api_key_ciphertext", length = 4096)
    private String apiKeyCiphertext;
    @Column(name = "credential_revision", nullable = false)
    private long credentialRevision = 1;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected IbmIntegration() {
    }

    public IbmIntegration(String portfolioId, String name) {
        this.id = UUID.randomUUID().toString();
        this.portfolioId = portfolioId;
        this.name = name;
        this.active = true;
    }

    public void update(String name, boolean active) {
        this.name = name;
        this.active = active;
    }

    public String getId() {
        return id;
    }
    public void configureConnection(String endpoint, String ciphertext) {
        this.credentialSource = "DATABASE"; this.serviceUrl = endpoint; this.apiKeyCiphertext = ciphertext;
    }
    public void rotateCredential(String ciphertext) { this.apiKeyCiphertext = ciphertext; this.credentialRevision++; }
    public String credentialContext() { return "ibm:" + portfolioId + ":" + id + ":" + serviceUrl; }
    public String getCredentialSource() { return credentialSource; }
    public String getServiceUrl() { return serviceUrl; }
    public String getApiKeyCiphertext() { return apiKeyCiphertext; }
    public long getCredentialRevision() { return credentialRevision; }

    public String getPortfolioId() {
        return portfolioId;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}
