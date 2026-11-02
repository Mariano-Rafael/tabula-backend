package com.tabula.tabula_backend.assistants.internal.application;

import com.tabula.tabula_backend.assistants.internal.domain.Assistant;
import com.tabula.tabula_backend.assistants.internal.domain.AssistantRepository;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AssistantService {

    private final AssistantRepository assistantRepository;

    public AssistantService(AssistantRepository assistantRepository) {
        this.assistantRepository = Objects.requireNonNull(assistantRepository);
    }

    public Assistant register(String name, String watsonAssistantId) {

        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Name cannot be null or blank");
        }

        Assistant assistant = new Assistant(
                name.trim(),
                watsonAssistantId.trim()
        );

        return assistantRepository.save(assistant);
    }

    public Optional<Assistant> findById(UUID id) {
        return assistantRepository.findById(id);
    }
}
