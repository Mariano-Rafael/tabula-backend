package com.tabula.tabula_backend.identity.model;

import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(name = "app_users")
public class AccountUser {
    @Id
    @Column(length = 36)
    private String id;
    @Column(nullable = false, length = 120)
    private String name;
    @Column(nullable = false, unique = true, length = 254)
    private String email;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Column(nullable = false)
    private boolean admin;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "auth_version", nullable = false)
    private long authVersion;
    @Version
    @Column(name = "row_version", nullable = false)
    private long rowVersion;

    protected AccountUser() {
    }

    public AccountUser(String name, String email, String passwordHash, boolean admin) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.email = email;
        this.passwordHash = passwordHash;
        this.admin = admin;
        this.active = true;
    }

    public void updateProfile(String name, String email) {
        this.name = name;
        if (!this.email.equals(email)) {
            this.authVersion++;
        }
        this.email = email;
    }

    public void updateAccess(boolean admin, boolean active) {
        if (this.admin != admin || this.active != active) {
            this.authVersion++;
        }
        this.admin = admin;
        this.active = active;
    }

    public void changePassword(String passwordHash) {
        this.passwordHash = passwordHash;
        this.authVersion++;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isAdmin() {
        return admin;
    }

    public boolean isActive() {
        return active;
    }

    public long getAuthVersion() {
        return authVersion;
    }
}
