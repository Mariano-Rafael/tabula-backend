package com.tabula.tabula_backend.portfolio.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "portfolio_id", nullable = false, length = 36)
    private String portfolioId;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, length = 1000)
    private String description;
    @Column(nullable = false)
    private boolean archived;
    @Version @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected Project() { }
    public Project(String portfolioId, String name, String description) {
        this.id = UUID.randomUUID().toString();
        this.portfolioId = portfolioId;
        this.name = name;
        this.description = description;
    }
    public void update(String name, String description) { this.name = name; this.description = description; }
    public void archive() { this.archived = true; }
    public String getId() { return id; }
    public String getPortfolioId() { return portfolioId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public boolean isArchived() { return archived; }
}
