package com.tabula.tabula_backend.flow.controller;

import com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.View;
import com.tabula.tabula_backend.flow.service.FlowLayoutService;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;

import java.util.UUID;

import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/flows/{id}/layout")
public class FlowLayoutController {
    private final FlowLayoutService service;

    public FlowLayoutController(FlowLayoutService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<View> find(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        return result(service.find(actor, id.toString()), false);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<View> save(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
                                     @RequestHeader(value = "If-Match", required = false) String match, @RequestHeader(value = "If-None-Match", required = false) String noneMatch,
                                     @RequestBody String raw) {
        return result(service.save(actor, id.toString(), match, noneMatch, raw), "*".equals(noneMatch));
    }

    private ResponseEntity<View> result(View view, boolean created) {
        return ResponseEntity.status(created ? HttpStatus.CREATED : HttpStatus.OK).eTag("\"layout-" + view.revision() + "\"")
                .cacheControl(CacheControl.noStore()).body(view);
    }
}
