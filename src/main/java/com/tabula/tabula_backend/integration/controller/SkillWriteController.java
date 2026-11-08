package com.tabula.tabula_backend.integration.controller;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.dto.SkillWriteDtos.*;
import com.tabula.tabula_backend.integration.service.SkillWriteService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class SkillWriteController {
    private final SkillWriteService service;
    public SkillWriteController(SkillWriteService service) { this.service = service; }
    @PostMapping("/projects/{id}/ibm-writes")
    public ResponseEntity<View> prepare(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody Prepare input, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var view = service.prepare(actor, id.toString(), input, key);
        return "READY".equals(view.state()) ? ResponseEntity.status(HttpStatus.CREATED).body(view) : result(view);
    }
    @GetMapping("/projects/{id}/ibm-writes")
    public com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView<View> list(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return service.list(actor, id.toString(), page, size); }
    @GetMapping("/ibm-writes/{id}")
    public View find(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return service.find(actor, id.toString()); }
    @GetMapping("/ibm-writes/{id}/backup")
    public ResponseEntity<String> backup(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(service.backup(actor, id.toString()).baselineJson());
    }
    @PostMapping("/ibm-writes/{id}/apply")
    public ResponseEntity<View> apply(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return result(service.apply(actor, id.toString())); }
    @PostMapping("/ibm-writes/{id}/reconcile")
    public ResponseEntity<View> reconcile(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return result(service.reconcile(actor, id.toString())); }
    private ResponseEntity<View> result(View view) {
        return ResponseEntity.status(switch (view.state()) {
            case "SUCCEEDED" -> HttpStatus.OK;
            case "CONFLICT" -> HttpStatus.CONFLICT;
            case "FAILED" -> HttpStatus.UNPROCESSABLE_ENTITY;
            default -> HttpStatus.ACCEPTED;
        }).body(view);
    }
}
