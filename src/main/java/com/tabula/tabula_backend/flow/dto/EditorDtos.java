package com.tabula.tabula_backend.flow.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public final class EditorDtos {
    private EditorDtos() { }
    public record Create(@NotBlank @Size(max=120) String name, UUID targetId) { }
    public record Bind(@NotNull UUID targetId) { }
    public record NewVersion(@NotBlank @Size(max=120) String name, UUID sourceVersionId) { }
    public record Rename(@NotBlank @Size(max=120) String name) { }
    public record Publish(@AssertTrue boolean confirmOverwrite) { }
    public record Version(String id, String flowId, String projectId, int number, String name,
                          long revision, String checksum, String authorId, String createdAt) { }
    public record Created(String flowId, String versionId, Version version, String targetId) { }
    public record Binding(String flowId, String targetId) { }
    public record Document(String id, String versionId, String flowId, String projectId, String portfolioId,
                           String checksum, String rawJson) { }
    public record Content(Version version, String rawJson) { }
}
