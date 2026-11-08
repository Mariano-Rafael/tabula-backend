package com.tabula.tabula_backend.history.controller;

import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import com.tabula.tabula_backend.conversation.service.ConversationMetadataService;
import com.tabula.tabula_backend.history.service.ConversationHistoryService;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1")
public class ConversationHistoryController {
    private final ConversationMetadataService metadata;
    private final ConversationHistoryService history;
    public ConversationHistoryController(ConversationMetadataService metadata, ConversationHistoryService history) { this.metadata = metadata; this.history = history; }
    @GetMapping("/projects/{id}/conversations")
    public PageView<View> list(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return metadata.list(actor, id.toString(), page, size); }
    @GetMapping("/conversations/{id}")
    public View find(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id) { return metadata.read(actor, id.toString()).view(); }
    @GetMapping("/conversations/{id}/messages")
    public PageView<TurnView> messages(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return history.messages(actor, id.toString(), page, size); }
    @GetMapping("/conversations/{id}/messages/{turnId}")
    public TurnView message(@AuthenticationPrincipal AccountPrincipal actor, @PathVariable UUID id, @PathVariable UUID turnId) { return history.message(actor, id.toString(), turnId.toString()); }
}
