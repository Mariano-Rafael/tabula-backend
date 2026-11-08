package com.tabula.tabula_backend.identity.service;

import com.tabula.tabula_backend.identity.dto.AccountDtos.*;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.*;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {
    private final UserRepository users;
    private final IdentityGuardRepository guard;
    private final PasswordEncoder passwords;
    private final LoginAttemptLimiter attempts;
    private final String dummyHash;

    public AccountService(UserRepository users, IdentityGuardRepository guard,
                          PasswordEncoder passwords, LoginAttemptLimiter attempts) {
        this.users = users;
        this.guard = guard;
        this.passwords = passwords;
        this.attempts = attempts;
        this.dummyHash = passwords.encode("unavailable-account-dummy-password");
    }

    @Transactional(readOnly = true)
    public AccountUser authenticate(Login input) {
        String email = normalizeEmail(input.email());
        attempts.check(email);
        AccountUser user = users.findByEmail(email).orElse(null);
        // BCrypt rejects passwords longer than 72 UTF-8 bytes.
        String candidate = input.password();
        boolean validLength = candidate.getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matches = passwords.matches(validLength ? candidate : "invalid-length", user == null ? dummyHash : user.getPasswordHash());
        if (!validLength || !matches || user == null || !user.isActive()) {
            throw error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email ou senha inválidos.");
        }
        attempts.success(email);
        return user;
    }

    @Transactional(readOnly = true)
    public View me(AccountPrincipal actor) {
        return View.from(current(actor));
    }

    public View create(AccountPrincipal actor, Create input) {
        guard.lockAccountChanges();
        requireAdmin(actor);
        return insert(input);
    }

    @Transactional(readOnly = true)
    public View findByAdmin(AccountPrincipal actor, String id) {
        requireAdmin(actor);
        return View.from(users.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND,
                "USER_NOT_FOUND", "Conta não encontrada.")));
    }

    public View updateProfile(AccountPrincipal actor, Profile input) {
        guard.lockAccountChanges();
        AccountUser user = current(actor);
        updateProfile(user, input.name(), input.email());
        return View.from(user);
    }

    public void changePassword(AccountPrincipal actor, Password input) {
        guard.lockAccountChanges();
        AccountUser user = current(actor);
        if (input.currentPassword().getBytes(StandardCharsets.UTF_8).length > 72
                || !passwords.matches(input.currentPassword(), user.getPasswordHash())) {
            throw error(HttpStatus.BAD_REQUEST, "CURRENT_PASSWORD_INVALID", "Senha atual inválida.");
        }
        validatePassword(input.newPassword());
        user.changePassword(passwords.encode(input.newPassword()));
    }

    public View updateByAdmin(AccountPrincipal actor, String id, AdminUpdate input) {
        guard.lockAccountChanges();
        requireAdmin(actor);
        AccountUser user = users.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Conta não encontrada."));
        boolean admin = input.admin() == null ? user.isAdmin() : input.admin();
        boolean active = input.active() == null ? user.isActive() : input.active();
        if (user.isAdmin() && user.isActive() && (!admin || !active) && users.countByAdminTrueAndActiveTrue() <= 1) {
            throw error(HttpStatus.CONFLICT, "LAST_ACTIVE_ADMIN", "O último administrador ativo deve ser mantido.");
        }
        updateProfile(user, input.name(), input.email());
        user.updateAccess(admin, active);
        return View.from(user);
    }

    public void bootstrap(String name, String email, String password) {
        guard.lockAccountChanges();
        // Never promote or overwrite an existing account using startup configuration.
        if (users.count() != 0) {
            return;
        }
        if (name == null || name.isBlank() || name.length() > 120 || email == null
                || email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalStateException("Configure nome e email válidos para o administrador inicial.");
        }
        insert(new Create(name, email, password, true));
    }

    private View insert(Create input) {
        validatePassword(input.password());
        String email = normalizeEmail(input.email());
        if (users.findByEmail(email).isPresent()) {
            throw emailConflict();
        }
        AccountUser user = new AccountUser(cleanName(input.name()), email, passwords.encode(input.password()), input.admin());
        return View.from(users.saveAndFlush(user));
    }

    private void updateProfile(AccountUser user, String name, String email) {
        String normalized = email == null ? user.getEmail() : normalizeEmail(email);
        if (users.existsByEmailAndIdNot(normalized, user.getId())) {
            throw emailConflict();
        }
        user.updateProfile(name == null ? user.getName() : cleanName(name), normalized);
    }

    private AccountUser current(AccountPrincipal principal) {
        if (principal == null) {
            throw error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "Autenticação necessária.");
        }
        AccountUser user = users.findById(principal.id()).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "Sessão inválida."));
        if (!user.isActive() || user.getAuthVersion() != principal.authVersion()) {
            throw error(HttpStatus.UNAUTHORIZED, "SESSION_INVALID", "Sessão inválida.");
        }
        return user;
    }

    private void requireAdmin(AccountPrincipal principal) {
        if (!current(principal).isAdmin()) {
            throw error(HttpStatus.FORBIDDEN, "ADMIN_REQUIRED", "Acesso administrativo necessário.");
        }
    }

    private static String cleanName(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 120) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_NAME", "Nome inválido.");
        }
        return name.strip();
    }

    public static String normalizeEmail(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static void validatePassword(String password) {
        if (password == null || password.isBlank() || password.length() < 8
                || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD", "A senha deve ter pelo menos 8 caracteres e no máximo 72 bytes UTF-8.");
        }
    }

    private static IdentityException emailConflict() {
        return error(HttpStatus.CONFLICT, "EMAIL_IN_USE", "Email já cadastrado.");
    }

    private static IdentityException error(HttpStatus status, String code, String message) {
        return new IdentityException(status, code, message);
    }
}
