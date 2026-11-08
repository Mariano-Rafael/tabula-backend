package com.tabula.tabula_backend.identity.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "identity_guard")
public class IdentityGuard {
    @Id
    private Integer id;

    protected IdentityGuard() {
    }
}
