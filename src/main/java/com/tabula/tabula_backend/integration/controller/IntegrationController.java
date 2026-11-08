package com.tabula.tabula_backend.integration.controller;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.service.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;
import jakarta.validation.Valid;

import java.net.URI;
import java.util.*;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class IntegrationController {
    private final IntegrationService service;
    private final ConnectionTestService tests;
    private final IbmSettings settings;

    public IntegrationController(IntegrationService service, ConnectionTestService tests, IbmSettings settings) {
        this.service = service;
        this.tests = tests;
        this.settings = settings;
    }

    @PostMapping("/portfolios/{id}/integrations")
    public ResponseEntity<IntegrationView> create(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
                                                  @Valid @RequestBody CreateIntegration input) {
        var result = service.create(actor, id.toString(), input);
        return ResponseEntity.created(URI.create("/api/v1/integrations/" + result.id())).body(result);
    }

    @GetMapping("/portfolios/{id}/integrations")
    public List<IntegrationView> list(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return service.list(actor, id.toString());
    }

    @GetMapping("/integrations/{id}")
    public IntegrationView find(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return service.find(actor, id.toString());
    }

    @PatchMapping("/integrations/{id}")
    public IntegrationView update(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
                                  @Valid @RequestBody UpdateIntegration input) {
        return service.update(actor, id.toString(), input);
    }

    @DeleteMapping("/integrations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        service.update(actor, id.toString(), new UpdateIntegration(null, false));
    }
    @PutMapping("/integrations/{id}/credentials")
    public IntegrationView rotateCredential(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @Valid @RequestBody RotateCredential input) {
        return service.rotateCredential(actor, id.toString(), input);
    }

    @GetMapping("/integrations/{id}/defaults")
    public TargetConfig defaults(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
                                 @RequestParam EnvironmentType environmentType) {
        return service.defaults(actor, id.toString(), environmentType, settings);
    }

    @PostMapping("/integrations/{id}/connection-tests")
    public ProbeResult connection(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return tests.integration(actor, id.toString());
    }

    @PostMapping("/projects/{id}/targets")
    public ResponseEntity<TargetView> createTarget(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
                                                   @Valid @RequestBody CreateTarget input) {
        var result = service.createTarget(actor, id.toString(), input);
        return ResponseEntity.created(URI.create("/api/v1/targets/" + result.id())).body(result);
    }

    @GetMapping("/projects/{id}/targets")
    public PageView<TargetView> targets(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
                                        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return service.targets(actor, id.toString(), page, size);
    }

    @GetMapping("/targets/{id}")
    public TargetView target(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return service.target(actor, id.toString());
    }

    @PutMapping("/targets/{id}")
    public TargetView updateTarget(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
                                   @Valid @RequestBody TargetConfig input) {
        return service.updateTarget(actor, id.toString(), input);
    }

    @DeleteMapping("/targets/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivateTarget(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        service.deactivateTarget(actor, id.toString());
    }

    @GetMapping("/targets/{id}/capabilities")
    public List<Capability> capabilities(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return service.capabilities(actor, id.toString());
    }

    @PostMapping("/targets/{id}/connection-tests")
    public ProbeResult targetConnection(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return tests.target(actor, id.toString());
    }
}
