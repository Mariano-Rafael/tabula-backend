package com.tabula.tabula_backend.assistants.internal.web;

import com.tabula.tabula_backend.assistants.internal.application.AssistantService;
import com.tabula.tabula_backend.assistants.internal.domain.Assistant;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/assistants")
public class AssistantController {

    private final AssistantService assistantService;

    public AssistantController(AssistantService assistantService) {
        this.assistantService = assistantService;
    }

    @PostMapping
    public ResponseEntity<AssistantResponse> register(
            @Valid @RequestBody CreateAssistantRequest request) {

        Assistant assistant = assistantService.register(
                request.name(),
                request.watsonAssistantId()
        );

        AssistantResponse response = new AssistantResponse(
                assistant.getId(),
                assistant.getName(),
                assistant.getWatsonAssistantId()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    public record CreateAssistantRequest(
            @NotBlank String name,
            @NotBlank String watsonAssistantId
    ) {}

    public record AssistantResponse(
            UUID id,
            String name,
            String watsonAssistantId
    ) {}

    @GetMapping("/{id}")
    public ResponseEntity<AssistantResponse> findById(
            @PathVariable UUID id) {

        return assistantService.findById(id)
                .map(assistant -> new AssistantResponse(
                        assistant.getId(),
                        assistant.getName(),
                        assistant.getWatsonAssistantId()
                ))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
