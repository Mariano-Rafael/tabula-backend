package com.tabula.tabula_backend.flow;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.client.*;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.service.*;
import java.util.*;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class IbmFlowExportTests {
    private final IntegrationService integrations = mock(IntegrationService.class);
    private final IbmHttpTransport http = mock(IbmHttpTransport.class);
    private final IbmTokenProvider tokens = mock(IbmTokenProvider.class);
    private final IbmFlowExportService service = new IbmFlowExportService(integrations, http, tokens);
    private final AccountPrincipal actor = new AccountPrincipal("user", 0);
    @BeforeEach
    void setup() {
        when(tokens.token()).thenReturn("token");
        target(EnvironmentType.DRAFT, "dialog", null);
    }
    private void target(EnvironmentType type, String dialog, String workspace) {
        when(integrations.targetForExport(actor, "target")).thenReturn(new TargetView("target", "project", "integration", true,
                new TargetConfig("Target", type, "assistant", "environment", workspace, dialog, "action", "2021-11-27", "2024-08-25")));
    }
    @Test
    void selectsExactConfiguredSkillAndPreservesUnknownFields() {
        var dialog = Map.<String, Object>of("type", "dialog", "skill_id", "dialog", "unknown", Map.of("future", true), "workspace", Map.of("dialog_nodes", List.of()));
        var action = Map.<String, Object>of("type", "action", "skill_id", "action", "workspace", Map.of("actions", List.of()));
        when(http.getExport(anyString(), anyString(), anyString(), anyMap())).thenReturn(Map.of("assistant_skills", List.of(action, dialog)));
        assertThat(service.export(actor, "project", "target", "DIALOG")).isEqualTo(dialog);
        assertThat(service.export(actor, "project", "target", "ACTION")).isEqualTo(action);
        verify(http, times(2)).getExport("/v2/assistants/assistant/skills_export", "2024-08-25", "token", Map.of());
    }
    @Test
    void processingFailureAndSkillMismatchHaveDistinctCodes() {
        when(http.getExport(anyString(), anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("status", "Processing"), Map.of("status", "Failed"), Map.of("assistant_skills", List.of()));
        for (String code : List.of("IBM_EXPORT_PENDING", "IBM_EXPORT_FAILED", "IBM_EXPORT_SKILL_MISMATCH")) {
            assertThatThrownBy(() -> service.export(actor, "project", "target", "DIALOG"))
                    .isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.code()).isEqualTo(code));
        }
    }
    @Test
    void liveTargetAndDifferentProjectAreRejectedBeforeRemoteCalls() {
        target(EnvironmentType.LIVE, "dialog", null);
        assertThatThrownBy(() -> service.export(actor, "project", "target", "DIALOG"))
                .isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.code()).isEqualTo("IBM_DRAFT_REQUIRED"));
        assertThatThrownBy(() -> service.export(actor, "other", "target", "DIALOG"))
                .isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.code()).isEqualTo("TARGET_PROJECT_MISMATCH"));
        verifyNoInteractions(http, tokens);
    }
    @Test
    void directV1WorkspaceUsesExportFlagOnlyWhenNoV2DialogSkillIsBound() {
        target(EnvironmentType.DRAFT, null, "workspace");
        var workspace = Map.<String, Object>of("dialog_nodes", List.of(), "unknown", true);
        when(http.getExport(anyString(), anyString(), anyString(), anyMap())).thenReturn(workspace);
        assertThat(service.export(actor, "project", "target", "DIALOG").get("workspace")).isEqualTo(workspace);
        verify(http).getExport("/v1/workspaces/workspace", "2021-11-27", "token", Map.of("export", "true"));
    }
}
