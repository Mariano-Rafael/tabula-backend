package com.tabula.tabula_backend.flow.controller;

import com.tabula.tabula_backend.flow.dto.FlowDiagramDtos.View;
import com.tabula.tabula_backend.flow.service.FlowDiagramService;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;

import java.util.UUID;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class FlowDiagramController {
    private final FlowDiagramService service;

    public FlowDiagramController(FlowDiagramService service) {
        this.service = service;
    }

    @GetMapping("/flows/{id}/diagram")
    public ResponseEntity<View> draft(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return result(service.draft(actor, id.toString()));
    }

    @GetMapping("/versions/{id}/diagram")
    public ResponseEntity<View> version(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return result(service.version(actor, id.toString()));
    }

    private ResponseEntity<View> result(View view) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view);
    }
}
