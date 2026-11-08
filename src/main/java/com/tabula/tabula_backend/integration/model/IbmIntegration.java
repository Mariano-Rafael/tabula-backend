package com.tabula.tabula_backend.integration.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name = "ibm_integrations")
public class IbmIntegration {
    @Id @Column(length = 36) private String id;
    @Column(name = "portfolio_id", nullable = false, length = 36) private String portfolioId;
    @Column(nullable = false, length = 120) private String name;
    @Column(nullable = false) private boolean active;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;
    protected IbmIntegration() { }
    public IbmIntegration(String portfolioId, String name) {
        this.id = UUID.randomUUID().toString(); this.portfolioId = portfolioId; this.name = name; this.active = true;
    }
    public void update(String name, boolean active) { this.name = name; this.active = active; }
    public String getId() { return id; }
    public String getPortfolioId() { return portfolioId; }
    public String getName() { return name; }
    public boolean isActive() { return active; }
}
