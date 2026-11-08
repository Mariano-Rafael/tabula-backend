package com.tabula.tabula_backend.portfolio.controller;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import jakarta.validation.Valid;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final PortfolioService service;

    public ProjectController(PortfolioService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ProjectView find(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return service.project(actor, id.toString());
    }

    @PatchMapping("/{id}")
    public ProjectView update(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @Valid @RequestBody Update input) {
        return service.updateProject(actor, id.toString(), input);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        service.archiveProject(actor, id.toString());
    }
}
