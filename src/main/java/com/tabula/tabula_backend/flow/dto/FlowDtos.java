package com.tabula.tabula_backend.flow.dto;

import com.tabula.tabula_backend.flow.model.*;
import jakarta.validation.constraints.*;
import java.util.UUID;

public final class FlowDtos {
    private FlowDtos() { }
    public enum FlowType { DIALOG, ACTION }
    public record Upload(@NotBlank @Size(max = 120) String name, @NotNull FlowType type, @NotBlank String json) { }
    public record IbmImport(@NotNull UUID targetId, @NotNull FlowType type, @Size(max = 120) String name, UUID flowId) { }
    public record Rename(@NotBlank @Size(max = 120) String name) { }
    public record StartDraft(@NotNull UUID versionId) { }
    public record View(String id, String projectId, String name, FlowType type, boolean archived) {
        public static View from(Flow f) { return new View(f.getId(), f.getProjectId(), f.getName(), FlowType.valueOf(f.getType()), f.isArchived()); }
    }
    public record VersionView(String id, String flowId, String projectId, int number, String state, String checksum,
            String sourceType, String sourceTargetId, String authorId, String createdAt, String errorCode) {
        public static VersionView from(FlowVersion v) { return new VersionView(v.getId(), v.getFlowId(), v.getProjectId(), v.getNumber(),
                v.getState(), v.getChecksum(), v.getSourceType(), v.getSourceTargetId(), v.getAuthorId(), v.getCreatedAt(), v.getErrorCode()); }
    }
    public record Snapshot(String documentId, String versionId, String flowId, String projectId, String portfolioId,
                           String checksum, String rawJson) { }
    public record Draft(String flowId, String portfolioId, long revision, String checksum, String rawJson) { }
    public record Reservation(VersionView version, Snapshot document) { }
}
