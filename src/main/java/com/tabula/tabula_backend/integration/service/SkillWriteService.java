package com.tabula.tabula_backend.integration.service;

import com.tabula.tabula_backend.flow.dto.FlowDtos.FlowType;
import com.tabula.tabula_backend.flow.service.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.client.*;
import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.dto.SkillWriteDtos.*;
import com.tabula.tabula_backend.integration.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.*;

/** SQL transitions and Mongo durability complete before HTTP. Never retries a remote mutation. */
@Service
public class SkillWriteService {
    private final IntegrationService integrations;
    private final PortfolioService portfolios;
    private final FlowMetadataService versions;
    private final FlowService flows;
    private final FlowJsonCodec codec;
    private final ObjectMapper json;
    private final IbmSettings settings;
    private final IbmFlowExportService exports;
    private final IbmSkillWriteGateway gateway;
    private final SkillWriteLedger ledger;
    private final SkillWriteDocuments documents;
    public SkillWriteService(IntegrationService integrations, PortfolioService portfolios, FlowMetadataService versions,
            FlowService flows, FlowJsonCodec codec, ObjectMapper json, IbmSettings settings, IbmFlowExportService exports,
            IbmSkillWriteGateway gateway, SkillWriteLedger ledger, SkillWriteDocuments documents) {
        this.integrations = integrations; this.portfolios = portfolios; this.versions = versions; this.flows = flows;
        this.codec = codec; this.json = json; this.settings = settings; this.exports = exports;
        this.gateway = gateway; this.ledger = ledger; this.documents = documents;
    }
    public View prepare(AccountPrincipal actor, String projectId, Prepare input, String requestKey) {
        portfolios.requireProjectEditing(actor, projectId);
        String key = FlowService.key(requestKey);
        String requestHash = codec.requestHash(Map.of("targetId", input.targetId().toString(), "versionId", input.versionId().toString()));
        var replay = ledger.existing(projectId, key);
        if (replay.isPresent()) {
            if (!requestHash.equals(replay.get().requestHash())) throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT");
            return replay.get().view();
        }
        var target = integrations.targetForExport(actor, input.targetId().toString());
        if (!projectId.equals(target.projectId())) throw error(HttpStatus.NOT_FOUND, "TARGET_PROJECT_MISMATCH");
        var c = target.configuration();
        if (c.environmentType() != EnvironmentType.DRAFT) throw error(HttpStatus.CONFLICT, "IBM_DRAFT_REQUIRED");
        var version = versions.version(actor, input.versionId().toString());
        if (!projectId.equals(version.projectId())) throw error(HttpStatus.NOT_FOUND, "FLOW_VERSION_MISMATCH");
        var flow = versions.editable(actor, version.flowId());
        String skillId = flow.type() == FlowType.DIALOG ? c.dialogSkillId() : c.actionSkillId();
        if (c.assistantId() == null || skillId == null) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "IBM_SKILL_REQUIRED");
        var binding = new Binding(c.assistantId(), skillId, c.v2Version());
        String candidate = workspace(parse(flows.export(actor, version.id())));
        String baseline = workspace(exports.exportSkill(binding.assistantId(), binding.skillId(), binding.version(), flow.type().name()));
        String id = UUID.randomUUID().toString();
        var view = new View(id, projectId, target.id(), version.id(), flow.type().name(), "PREPARING", codec.checksum(baseline),
                codec.checksum(candidate), actor.id(), Instant.now().toString(), null);
        var row = new Row(view, codec.encode(binding), codec.requestHash(Map.of("endpoint", settings.serviceUrl(), "assistant", c.assistantId(), "skill", skillId)), requestHash, SkillWriteLedger.deadline());
        ledger.insert(row, key);
        try {
            documents.put(new Documents(id, projectId, baseline, candidate));
            ledger.ready(id);
        } catch (RuntimeException ex) { ledger.finish(id, "FAILED", "IBM_WRITE_DOCUMENT_UNAVAILABLE"); throw ex; }
        return ledger.get(id).view();
    }
    public View find(AccountPrincipal actor, String id) {
        var row = ledger.get(id); portfolios.project(actor, row.view().projectId()); return row.view();
    }
    public com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView<View> list(AccountPrincipal actor, String projectId, int page, int size) {
        portfolios.project(actor, projectId);
        if (page < 0 || size < 1 || size > 100) throw error(HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        return ledger.list(projectId, page, size);
    }
    public Documents backup(AccountPrincipal actor, String id) {
        var row = ledger.get(id); portfolios.requireProjectEditing(actor, row.view().projectId()); return verified(row);
    }
    public View apply(AccountPrincipal actor, String id) {
        var row = editable(actor, id, true);
        if (!"READY".equals(row.view().state())) return row.view();
        var d = verified(row);
        if (!ledger.claim(id)) return ledger.get(id).view();
        try {
            var frozen = binding(row);
            var current = exports.exportSkill(frozen.assistantId(), frozen.skillId(), frozen.version(), row.view().type());
            if (!codec.checksum(workspace(current)).equals(row.view().baselineHash())) {
                ledger.finish(id, "CONFLICT", "IBM_REMOTE_CHANGED"); return ledger.get(id).view();
            }
        } catch (RuntimeException ex) {
            if (ex instanceof IntegrationException pending && pending.status() == HttpStatus.ACCEPTED) {
                ledger.retryPreflight(id); return ledger.get(id).view();
            }
            ledger.finish(id, "FAILED", ex instanceof IntegrationException e ? e.code() : "IBM_PREFLIGHT_FAILED"); throw ex;
        }
        // Persist SENDING before dispatch; a crash or timeout after this point is uncertain.
        if (!ledger.sending(id)) return ledger.get(id).view();
        try {
            gateway.update(binding(row), parse(d.candidateJson()));
            ledger.dispatched(id);
        } catch (IntegrationException ex) {
            boolean rejected = Set.of("IBM_REQUEST_REJECTED", "IBM_AUTHENTICATION_FAILED", "IBM_PERMISSION_DENIED", "IBM_RESOURCE_NOT_FOUND", "IBM_OPERATION_NOT_SUPPORTED", "IBM_RATE_LIMITED").contains(ex.code());
            ledger.finish(id, rejected ? "FAILED" : "UNKNOWN", ex.code());
        } catch (RuntimeException ex) { ledger.finish(id, "UNKNOWN", "IBM_WRITE_RESULT_UNKNOWN"); }
        return ledger.get(id).view();
    }
    public View reconcile(AccountPrincipal actor, String id) {
        var row = editable(actor, id, false);
        var state = row.view().state();
        if (Set.of("PREPARING", "CHECKING").contains(state)) { ledger.expireUnsent(id); return ledger.get(id).view(); }
        if ("SENDING".equals(state)) {
            if (row.leaseUntil() >= System.currentTimeMillis()) return row.view();
            ledger.finish(id, "UNKNOWN", "IBM_WRITE_RESULT_UNKNOWN");
        } else if (!Set.of("PROCESSING", "UNKNOWN").contains(state)) return row.view();
        verified(row);
        var binding = binding(row);
        var status = gateway.status(binding);
        if ("Failed".equals(status.get("status"))) {
            ledger.finish(id, "FAILED", "IBM_SKILL_UPDATE_FAILED"); return ledger.get(id).view();
        }
        if (!"Available".equals(status.get("status"))) return ledger.get(id).view();
        // Read the frozen assistant/skill, even if the target was subsequently edited or disabled.
        var exported = exports.exportSkill(binding.assistantId(), binding.skillId(), binding.version(), row.view().type());
        if (codec.checksum(workspace(exported)).equals(row.view().candidateHash())) ledger.finish(id, "SUCCEEDED", null);
        else ledger.finish(id, "UNKNOWN", "IBM_WRITE_CONTENT_NOT_CONFIRMED");
        return ledger.get(id).view();
    }
    private Row editable(AccountPrincipal actor, String id, boolean active) {
        var row = ledger.get(id);
        portfolios.requireProjectEditing(actor, row.view().projectId(), !active);
        var b = binding(row);
        String resource = codec.requestHash(Map.of("endpoint", settings.serviceUrl(), "assistant", b.assistantId(), "skill", b.skillId()));
        if (!row.resourceHash().equals(resource)) throw error(HttpStatus.CONFLICT, "IBM_WRITE_TARGET_CHANGED");
        if (active) {
            var current = integrations.targetForExport(actor, row.view().targetId()).configuration();
            String skill = "DIALOG".equals(row.view().type()) ? current.dialogSkillId() : current.actionSkillId();
            if (current.environmentType() != EnvironmentType.DRAFT || !Objects.equals(b.assistantId(), current.assistantId())
                    || !Objects.equals(b.skillId(), skill) || !b.version().equals(current.v2Version())) throw error(HttpStatus.CONFLICT, "IBM_WRITE_TARGET_CHANGED");
        }
        return row;
    }
    private Documents verified(Row row) {
        var d = documents.get(row.view().projectId(), row.view().id());
        if (!d.id().equals(row.view().id()) || !d.projectId().equals(row.view().projectId())
                || !codec.checksum(d.baselineJson()).equals(row.view().baselineHash()) || !codec.checksum(d.candidateJson()).equals(row.view().candidateHash())) {
            throw error(HttpStatus.CONFLICT, "IBM_WRITE_INTEGRITY_ERROR");
        }
        return d;
    }
    private Binding binding(Row row) { return json.readValue(row.bindingJson(), Binding.class); }
    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String value) {
        return (Map<String, Object>) json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(value);
    }
    private String workspace(Map<String, Object> skill) {
        if (!(skill.get("workspace") instanceof Map<?, ?> workspace)) throw error(HttpStatus.BAD_GATEWAY, "IBM_WORKSPACE_INVALID");
        String raw = codec.encode(workspace); codec.checksum(raw); return raw;
    }
    private static IntegrationException error(HttpStatus status, String code) {
        return new IntegrationException(status, code, "Operação IBM não confirmada. Consulte o código e o estado antes de prosseguir.");
    }
}
