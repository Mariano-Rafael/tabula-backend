package com.tabula.tabula_backend.assistants.internal.domain;

import java.util.Optional;
import java.util.UUID;

public interface AssistantRepository {

    Assistant save(Assistant assistant);

    Optional<Assistant> findById(UUID id);
}
