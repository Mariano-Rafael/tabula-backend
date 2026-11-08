package com.tabula.tabula_backend.integration.dto;

import com.tabula.tabula_backend.integration.model.*;
import jakarta.validation.constraints.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IntegrationDtos {
    private IntegrationDtos() {
    }

    public enum EnvironmentType {DRAFT, LIVE}

    public record CreateIntegration(@NotBlank @Size(max = 120) String name, @Size(max = 512) String serviceUrl,
            @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) @Size(max = 2048) String apiKey) {
        public CreateIntegration(String name) { this(name, null, null); }
        @Override public String toString() { return "CreateIntegration[credentials=REDACTED]"; }
    }
    public record RotateCredential(@NotBlank @Size(max = 2048)
            @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String apiKey) {
        @Override public String toString() { return "RotateCredential[REDACTED]"; }
    }

    public record UpdateIntegration(@Size(min = 1, max = 120) String name, Boolean active) {
    }

    public record IntegrationView(String id, String portfolioId, String name, boolean active, String provider, String credentialSource, String serviceUrl, long credentialRevision) {
        public static IntegrationView from(IbmIntegration i) {
            return new IntegrationView(i.getId(), i.getPortfolioId(), i.getName(), i.isActive(), "IBM", i.getCredentialSource(), i.getServiceUrl(), i.getCredentialRevision());
        }
    }

    // PUT is full replacement. Null resource identifiers clear the corresponding binding.
    public record TargetConfig(@NotBlank @Size(max = 120) String name,
                               @NotNull EnvironmentType environmentType,
                               @Size(max = 128) String assistantId, @Size(max = 128) String environmentId,
                               @Size(max = 128) String dialogWorkspaceId, @Size(max = 128) String dialogSkillId,
                               @Size(max = 128) String actionSkillId,
                               @NotBlank String v1Version, @NotBlank String v2Version) {
    }

    public record CreateTarget(@NotNull UUID integrationId, @NotNull @jakarta.validation.Valid TargetConfig configuration) {
    }

    public record TargetView(String id, String projectId, String integrationId, boolean active, TargetConfig configuration) {
        public static TargetView from(IbmTarget t) {
            return new TargetView(t.getId(), t.getProjectId(), t.getIntegrationId(), t.isActive(), new TargetConfig(t.getName(),
                    EnvironmentType.valueOf(t.getEnvironmentType()), t.getAssistantId(), t.getEnvironmentId(),
                    t.getDialogWorkspaceId(), t.getDialogSkillId(), t.getActionSkillId(), t.getV1Version(), t.getV2Version()));
        }
    }

    public record Check(String resource, String status, String code) {
    }

    public record ProbeResult(Instant checkedAt, boolean successful, List<Check> checks) {
    }

    public record Capability(String operation, boolean implemented, boolean configured, String permissionStatus) {
    }
}
