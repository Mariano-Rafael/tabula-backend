package com.tabula.tabula_backend.assistants.internal.domain;

import java.util.Objects;
import java.util.UUID;

public class Assistant {

    private final UUID id;
    private String name;
    private final String watsonAssistantId;

    public Assistant(String name, String watsonAssistantId) {
        this.id = UUID.randomUUID();
        this.name = Objects.requireNonNull(name);
        this.watsonAssistantId = Objects.requireNonNull(watsonAssistantId);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getWatsonAssistantId() {
        return watsonAssistantId;
    }

    public void rename(String name) {
        this.name = Objects.requireNonNull(name);
    }
}
