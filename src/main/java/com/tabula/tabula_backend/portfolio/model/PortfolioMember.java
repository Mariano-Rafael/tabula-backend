package com.tabula.tabula_backend.portfolio.model;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "portfolio_members")
public class PortfolioMember {
    @Id @Column(length = 36)
    private String id;
    @Column(name = "portfolio_id", nullable = false, length = 36)
    private String portfolioId;
    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private PortfolioRole role;

    protected PortfolioMember() { }
    public PortfolioMember(String portfolioId, String userId, PortfolioRole role) {
        this.id = UUID.randomUUID().toString();
        this.portfolioId = portfolioId;
        this.userId = userId;
        this.role = role;
    }
    public void changeRole(PortfolioRole role) { this.role = role; }
    public String getUserId() { return userId; }
    public PortfolioRole getRole() { return role; }
}
