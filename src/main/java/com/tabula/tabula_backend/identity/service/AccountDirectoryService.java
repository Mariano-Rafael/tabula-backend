package com.tabula.tabula_backend.identity.service;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Identity contract for other modules; never exposes persistence entities or hashes. */
@Service
@Transactional(readOnly = true)
public class AccountDirectoryService {
    private final UserRepository users;
    public AccountDirectoryService(UserRepository users) { this.users = users; }

    public record Access(String id, boolean admin) { }

    public Access current(AccountPrincipal principal) {
        if (principal == null) { throw invalidSession(); }
        var user = users.findById(principal.id()).orElseThrow(AccountDirectoryService::invalidSession);
        if (!user.isActive() || user.getAuthVersion() != principal.authVersion()) { throw invalidSession(); }
        return new Access(user.getId(), user.isAdmin());
    }

    public boolean isActive(String userId) {
        return users.findById(userId).map(user -> user.isActive()).orElse(false);
    }

    private static IdentityException invalidSession() {
        return new IdentityException(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "Sessão inválida.");
    }
}
