package com.tabula.tabula_backend.identity.controller;

import com.tabula.tabula_backend.identity.dto.AccountDtos.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.identity.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/account")
public class AccountController {
    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PatchMapping
    public View update(@AuthenticationPrincipal AccountPrincipal actor, @Valid @RequestBody Profile input,
                       HttpServletRequest request) {
        View before = accounts.me(actor);
        View result = accounts.updateProfile(actor, input);
        if (!before.email().equals(result.email())) {
            invalidate(request);
        }
        return result;
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@AuthenticationPrincipal AccountPrincipal actor, @Valid @RequestBody Password input,
                         HttpServletRequest request) {
        accounts.changePassword(actor, input);
        invalidate(request);
    }

    private static void invalidate(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
    }
}
