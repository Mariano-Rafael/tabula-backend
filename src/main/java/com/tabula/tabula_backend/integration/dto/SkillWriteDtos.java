package com.tabula.tabula_backend.integration.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public final class SkillWriteDtos {
    private SkillWriteDtos() {
    }

    public record Prepare(@NotNull UUID targetId, @NotNull UUID versionId) {
    }

    public record View(String id, String projectId, String targetId, String versionId, String type,
                       String state, String baselineHash, String candidateHash, String createdBy,
                       String createdAt, String errorCode) {
    }

    public record Binding(String assistantId, String skillId, String version, com.tabula.tabula_backend.integration.client.IbmConnectionRef connection) {
        public Binding(String assistantId, String skillId, String version) { this(assistantId, skillId, version, null); }
    }

    public record Row(View view, String bindingJson, String resourceHash, String requestHash, long leaseUntil) {
    }

    public record Documents(String id, String projectId, String baselineJson, String candidateJson) {
    }
}
