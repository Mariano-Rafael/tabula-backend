package com.tabula.tabula_backend.integration.service;

import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.client.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Obtains a single skill/workspace without a database transaction across IBM calls. */
@Service
public class IbmFlowExportService {
    private final IntegrationService integrations;
    private final IbmHttpTransport http;
    private final IbmTokenProvider tokens;
    public IbmFlowExportService(IntegrationService integrations, IbmHttpTransport http, IbmTokenProvider tokens) {
        this.integrations = integrations; this.http = http; this.tokens = tokens;
    }
    public Map<String, Object> export(AccountPrincipal actor, String projectId, String targetId, String type) {
        var target = integrations.targetForExport(actor, targetId);
        if (!target.projectId().equals(projectId)) { throw error(HttpStatus.NOT_FOUND, "TARGET_PROJECT_MISMATCH", "Destino não pertence ao projeto."); }
        var c = target.configuration();
        String skillId = "DIALOG".equals(type) ? c.dialogSkillId() : c.actionSkillId();
        try {
            if (c.assistantId() != null && skillId != null) {
                if (!"DRAFT".equals(c.environmentType().name())) {
                    throw error(HttpStatus.CONFLICT, "IBM_DRAFT_REQUIRED", "A exportação de skills usa o rascunho do assistant; selecione um destino DRAFT.");
                }
                var result = http.getExport("/v2/assistants/" + IbmEndpointPolicy.identifier(c.assistantId()) + "/skills_export",
                        c.v2Version(), tokens.token(), Map.of());
                if ("Failed".equals(result.get("status"))) { throw error(HttpStatus.BAD_GATEWAY, "IBM_EXPORT_FAILED", "A exportação IBM falhou."); }
                if (!(result.get("assistant_skills") instanceof List<?> skills)) {
                    if ("Processing".equals(result.get("status")) || "Pending".equals(result.get("status"))) {
                        throw error(HttpStatus.ACCEPTED, "IBM_EXPORT_PENDING", "Exportação em processamento. Repita a requisição após alguns segundos.");
                    }
                    throw error(HttpStatus.BAD_GATEWAY, "IBM_EXPORT_INVALID", "A IBM não retornou as skills exportadas.");
                }
                String expectedType = "DIALOG".equals(type) ? "dialog" : "action";
                var matches = skills.stream().filter(Map.class::isInstance).map(Map.class::cast)
                        .filter(s -> skillId.equals(s.get("skill_id")) && expectedType.equals(s.get("type"))).toList();
                if (matches.size() != 1) { throw error(HttpStatus.BAD_GATEWAY, "IBM_EXPORT_SKILL_MISMATCH", "A exportação não contém exatamente a skill configurada."); }
                @SuppressWarnings("unchecked") Map<String, Object> selected = matches.getFirst();
                return selected;
            }
            if ("DIALOG".equals(type) && c.dialogWorkspaceId() != null) {
                var workspace = http.getExport("/v1/workspaces/" + IbmEndpointPolicy.identifier(c.dialogWorkspaceId()),
                        c.v1Version(), tokens.token(), Map.of("export", "true"));
                // Common skill envelope for local storage; the workspace JSON is kept intact.
                return Map.of("type", "dialog", "workspace", workspace);
            }
            throw error(HttpStatus.BAD_REQUEST, "IBM_FLOW_NOT_CONFIGURED", "Configure a skill correspondente ou um workspace de Dialogs.");
        } catch (IntegrationException ex) {
            if ("IBM_AUTHENTICATION_FAILED".equals(ex.code())) { tokens.invalidate(); }
            throw ex;
        }
    }
    private static IntegrationException error(HttpStatus status, String code, String message) { return new IntegrationException(status, code, message); }
}
