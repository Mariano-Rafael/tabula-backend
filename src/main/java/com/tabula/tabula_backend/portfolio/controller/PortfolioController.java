package com.tabula.tabula_backend.portfolio.controller;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {
    private final PortfolioService service;
    public PortfolioController(PortfolioService service) { this.service = service; }

    @PostMapping
    public ResponseEntity<PortfolioView> create(@AuthenticationPrincipal AccountPrincipal actor, @Valid @RequestBody Create input) {
        var view = service.create(actor, input);
        return ResponseEntity.created(URI.create("/api/v1/portfolios/" + view.id())).body(view);
    }
    @GetMapping
    public PageView<PortfolioView> list(@AuthenticationPrincipal AccountPrincipal actor,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.list(actor, includeArchived, page, size);
    }
    @GetMapping("/{id}")
    public PortfolioView find(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return service.find(actor, id.toString());
    }
    @PatchMapping("/{id}")
    public PortfolioView update(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @Valid @RequestBody Update input) {
        return service.update(actor, id.toString(), input);
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { service.archive(actor, id.toString()); }

    @GetMapping("/{id}/members")
    public PageView<MemberView> members(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.members(actor, id.toString(), page, size);
    }
    @PostMapping("/{id}/members") @ResponseStatus(HttpStatus.CREATED)
    public MemberView addMember(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @Valid @RequestBody AddMember input) {
        return service.addMember(actor, id.toString(), input);
    }
    @PatchMapping("/{id}/members/{userId}")
    public MemberView changeRole(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @PathVariable UUID userId, @Valid @RequestBody ChangeRole input) {
        return service.changeRole(actor, id.toString(), userId.toString(), input);
    }
    @DeleteMapping("/{id}/members/{userId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @PathVariable UUID userId) {
        service.removeMember(actor, id.toString(), userId.toString());
    }
    @PostMapping("/{id}/projects")
    public ResponseEntity<ProjectView> createProject(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody Create input) {
        var view = service.createProject(actor, id.toString(), input);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + view.id())).body(view);
    }
    @GetMapping("/{id}/projects")
    public PageView<ProjectView> projects(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.projects(actor, id.toString(), includeArchived, page, size);
    }
}
