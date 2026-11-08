package com.tabula.tabula_backend.flow.controller;

import com.tabula.tabula_backend.flow.service.DialogNodeEditService;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/flows/{id}/draft/dialog-nodes")
public class DialogNodeEditController {
    private final DialogNodeEditService service;
    public DialogNodeEditController(DialogNodeEditService service) { this.service = service; }
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> edit(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestHeader(value = "If-Match", required = false) String revision, @RequestBody String raw) {
        var draft = service.edit(actor, id.toString(), revision, raw);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).cacheControl(CacheControl.noStore())
                .eTag("\""+draft.revision()+"\"").body(draft.rawJson());
    }
}
