package com.tabula.tabula_backend.identity.model;

import java.io.Serializable;

// Session data deliberately excludes password hashes and IBM credentials.
public record AccountPrincipal(String id, long authVersion) implements Serializable {
}
