package com.tabula.tabula_backend.conversation.controller;

import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import com.tabula.tabula_backend.conversation.service.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class ConversationController {
    private final ConversationService service;
    private final ConversationMetadataService metadata;
    public ConversationController(ConversationService service, ConversationMetadataService metadata) { this.service = service; this.metadata = metadata; }
    @PostMapping("/projects/{id}/conversations")
    public ResponseEntity<View> open(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody Open input, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var view = service.open(actor, id.toString(), input, key);
        HttpStatus status = switch (view.state()) {
            case "ACTIVE" -> HttpStatus.CREATED;
            case "OPEN_FAILED" -> HttpStatus.BAD_GATEWAY;
            case "OPENING", "OPEN_UNKNOWN" -> HttpStatus.ACCEPTED;
            default -> HttpStatus.OK;
        };
        return ResponseEntity.status(status).location(URI.create("/api/v1/conversations/" + view.id())).body(view);
    }
    @PostMapping("/conversations/{id}/messages")
    public ResponseEntity<TurnView> message(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @Valid @RequestBody Message input, @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        var view = service.message(actor, id.toString(), input, key);
        return ResponseEntity.status(messageStatus(view)).location(URI.create("/api/v1/conversations/" + id + "/messages/" + view.id())).body(view);
    }
    @PostMapping("/conversations/{id}/messages/{turnId}/reconcile")
    public ResponseEntity<TurnView> reconcileMessage(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @PathVariable UUID turnId) {
        var view = service.reconcileMessage(actor, id.toString(), turnId.toString());
        return ResponseEntity.status(messageStatus(view)).body(view);
    }
    @PostMapping("/conversations/{id}/close")
    public ResponseEntity<View> close(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) {
        var view = service.close(actor, id.toString());
        return ResponseEntity.status("CLOSED".equals(view.state()) ? HttpStatus.OK : HttpStatus.ACCEPTED).body(view);
    }
    @PostMapping("/conversations/{id}/reconcile")
    public View reconcile(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return metadata.reconcileLifecycle(actor, id.toString()); }
    private static HttpStatus messageStatus(TurnView view) {
        return "COMPLETED".equals(view.state()) ? HttpStatus.OK : "FAILED".equals(view.state()) ? HttpStatus.UNPROCESSABLE_ENTITY : HttpStatus.ACCEPTED;
    }
}
