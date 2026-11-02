package com.tabula.tabula_backend.assistants.internal.infra;

import com.tabula.tabula_backend.assistants.internal.domain.Assistant;
import com.tabula.tabula_backend.assistants.internal.domain.AssistantRepository;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class InMemoryAssistantRepository implements AssistantRepository {

    private final Map<UUID, Assistant> assistants = new ConcurrentHashMap<>();

    @Override
    public Assistant save(Assistant assistant) {
        assistants.put(assistant.getId(), assistant);
        return assistant;
    }

    @Override
    public Optional<Assistant> findById(UUID id) {
        return Optional.ofNullable(assistants.get(id));
    }
}
