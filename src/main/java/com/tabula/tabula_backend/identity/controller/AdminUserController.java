package com.tabula.tabula_backend.identity.controller;

import com.tabula.tabula_backend.identity.dto.AccountDtos.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.identity.service.AccountService;
import jakarta.validation.Valid;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/users")
public class AdminUserController {
    private final AccountService accounts;

    public AdminUserController(AccountService accounts) {
        this.accounts = accounts;
    }

    @PostMapping
    public ResponseEntity<View> create(@AuthenticationPrincipal AccountPrincipal actor, @Valid @RequestBody Create input) {
        View result = accounts.create(actor, input);
        return ResponseEntity.created(URI.create("/api/v1/admin/users/" + result.id())).body(result);
    }

    @PatchMapping("/{id}")
    public View update(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
                       @Valid @RequestBody AdminUpdate input) {
        return accounts.updateByAdmin(actor, id.toString(), input);
    }

    @GetMapping("/{id}")
    public View find(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return accounts.findByAdmin(actor, id.toString());
    }
}
