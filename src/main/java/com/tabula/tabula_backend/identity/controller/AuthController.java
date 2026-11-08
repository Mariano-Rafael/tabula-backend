package com.tabula.tabula_backend.identity.controller;

import com.tabula.tabula_backend.identity.dto.AccountDtos.*;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.service.AccountService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;

import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AccountService accounts;
    private final SecurityContextRepository contexts;

    public AuthController(AccountService accounts, SecurityContextRepository contexts) {
        this.accounts = accounts;
        this.contexts = contexts;
    }

    public record CsrfView(String headerName, String token) {
    }

    @GetMapping("/csrf")
    public CsrfView csrf(CsrfToken token) {
        return new CsrfView(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    public View login(@Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
        AccountUser user = accounts.authenticate(input);
        var principal = new AccountPrincipal(user.getId(), user.getAuthVersion());
        var authorities = List.of(new SimpleGrantedAuthority(user.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER"));
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        // Rotate CSRF on authentication; client must fetch a fresh token afterwards.
        new HttpSessionCsrfTokenRepository().saveToken(null, request, response);
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return View.from(user);
    }

    @GetMapping("/me")
    public View me(@AuthenticationPrincipal AccountPrincipal principal) {
        return accounts.me(principal);
    }
}
