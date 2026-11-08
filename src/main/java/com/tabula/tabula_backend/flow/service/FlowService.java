package com.tabula.tabula_backend.flow.service;

import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import com.tabula.tabula_backend.flow.repository.FlowDocumentStore;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.service.IbmFlowExportService;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class FlowService {
    private final FlowMetadataService metadata;
    private final FlowDocumentStore documents;
    private final FlowJsonCodec json;
    private final IbmFlowExportService ibm;
    private final PortfolioService portfolios;

    public FlowService(FlowMetadataService metadata, FlowDocumentStore documents, FlowJsonCodec json,
                       IbmFlowExportService ibm, PortfolioService portfolios) {
        this.metadata = metadata;
        this.documents = documents;
        this.json = json;
        this.ibm = ibm;
        this.portfolios = portfolios;
    }

    public VersionView upload(AccountPrincipal actor, String projectId, Upload input, String requestKey) {
        portfolios.requireProjectEditing(actor, projectId);
        String key = key(requestKey);
        String checksum = json.validate(input.json(), input.type());
        String hash = json.requestHash(Map.of("operation", "UPLOAD", "name", input.name(), "type", input.type(), "checksum", checksum));
        var existing = metadata.existing(actor, projectId, key, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        return persist(metadata.reserve(actor, projectId, null, input.name(), input.type(), key, hash, checksum, input.json(), "UPLOAD", null));
    }

    public VersionView importIbm(AccountPrincipal actor, String projectId, IbmImport input, String requestKey) {
        String key = key(requestKey);
        String hash = json.requestHash(Map.of("operation", "IBM", "request", input));
        var existing = metadata.existing(actor, projectId, key, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Authorization above and inside the gateway completes before the export HTTP request.
        var exported = ibm.export(actor, projectId, input.targetId().toString(), input.type().name());
        String raw = json.encode(exported);
        String checksum = json.validate(raw, input.type());
        String name = input.name() != null ? input.name() : exported.get("name") instanceof String text ? text : "Imported " + input.type();
        return persist(metadata.reserve(actor, projectId, input.flowId() == null ? null : input.flowId().toString(), name, input.type(), key,
                hash, checksum, raw, "IBM", input.targetId().toString()));
    }

    public String export(AccountPrincipal actor, String versionId) {
        var version = metadata.version(actor, versionId);
        if (!"READY".equals(version.state())) {
            throw error(HttpStatus.CONFLICT, "FLOW_VERSION_NOT_READY", "Versão ainda não está disponível para leitura.");
        }
        return verified(metadata.expected(actor, versionId, false)).rawJson();
    }

    public Draft startDraft(AccountPrincipal actor, String flowId, String versionId) {
        metadata.editable(actor, flowId);
        var version = metadata.version(actor, versionId);
        if (!flowId.equals(version.flowId())) {
            throw error(HttpStatus.NOT_FOUND, "FLOW_VERSION_MISMATCH", "Versão não pertence ao fluxo.");
        }
        if (!"READY".equals(version.state())) {
            throw error(HttpStatus.CONFLICT, "FLOW_VERSION_NOT_READY", "Versão ainda não está disponível.");
        }
        var expected = metadata.expected(actor, versionId, true);
        var source = verified(expected);
        return documents.initializeDraft(flowId, expected.portfolioId(), source.rawJson(), source.checksum());
    }

    public Draft draft(AccountPrincipal actor, String flowId) {
        var flow = metadata.find(actor, flowId);
        String portfolioId = portfolios.project(actor, flow.projectId()).portfolioId();
        var draft = documents.draft(portfolioId, flowId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "DRAFT_NOT_FOUND", "Rascunho não encontrado."));
        if (!flowId.equals(draft.flowId()) || !portfolioId.equals(draft.portfolioId()) || !draft.checksum().equals(json.checksum(draft.rawJson()))) {
            throw error(HttpStatus.CONFLICT, "FLOW_INTEGRITY_ERROR", "Integridade do rascunho incompatível.");
        }
        return draft;
    }

    public Draft updateDraft(AccountPrincipal actor, String flowId, String ifMatch, String raw) {
        var flow = metadata.editable(actor, flowId);
        String portfolioId = portfolios.project(actor, flow.projectId()).portfolioId();
        long revision = revision(ifMatch);
        String checksum = json.validate(raw, flow.type());
        return documents.updateDraft(portfolioId, flowId, revision, raw, checksum);
    }

    public VersionView snapshotDraft(AccountPrincipal actor, String flowId, String ifMatch, String requestKey) {
        var flow = metadata.editable(actor, flowId);
        long revision = revision(ifMatch);
        String key = key(requestKey);
        String hash = json.requestHash(Map.of("operation", "DRAFT", "flowId", flowId, "revision", revision));
        var existing = metadata.existing(actor, flow.projectId(), key, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        var draft = draft(actor, flowId);
        if (draft.revision() != revision) {
            throw error(HttpStatus.PRECONDITION_FAILED, "DRAFT_REVISION_MISMATCH", "Atualize a revisão do rascunho.");
        }
        String checksum = json.validate(draft.rawJson(), flow.type());
        return persist(metadata.reserve(actor, flow.projectId(), flowId, flow.name(), flow.type(), key, hash,
                checksum, draft.rawJson(), "DRAFT", null));
    }

    public VersionView reconcile(AccountPrincipal actor, String versionId) {
        var expected = metadata.expected(actor, versionId, true);
        var stored = documents.snapshot(expected.portfolioId(), expected.documentId());
        if (stored.isPresent()) {
            verify(expected, stored.get());
            return metadata.complete(versionId);
        }
        var current = metadata.version(actor, versionId);
        if ("READY".equals(current.state())) {
            throw error(HttpStatus.SERVICE_UNAVAILABLE, "FLOW_DOCUMENT_MISSING", "Documento de uma versão pronta não foi encontrado.");
        }
        if (!metadata.leaseExpired(versionId)) {
            throw error(HttpStatus.CONFLICT, "FLOW_WRITE_IN_PROGRESS", "Gravação ainda pode estar em andamento. Tente mais tarde.");
        }
        metadata.fail(versionId, "FLOW_DOCUMENT_MISSING");
        return metadata.version(actor, versionId);
    }

    private VersionView persist(Reservation reservation) {
        if (reservation.document() == null) {
            return reservation.version();
        }
        try {
            documents.putSnapshot(reservation.document());
        } catch (FlowException ex) {
            try {
                metadata.fail(reservation.version().id(), ex.code());
            } catch (RuntimeException ignored) { /* Pending reservation remains reconcilable. */ }
            throw ex;
        }
        // If this transaction fails after Mongo succeeds, do not overwrite the persisted document.
        // Reconciliation can confirm its hash and finish the original reservation.
        return metadata.complete(reservation.version().id());
    }

    private Snapshot verified(Snapshot expected) {
        var stored = documents.snapshot(expected.portfolioId(), expected.documentId())
                .orElseThrow(() -> error(HttpStatus.SERVICE_UNAVAILABLE, "FLOW_DOCUMENT_MISSING", "Documento do fluxo não encontrado."));
        verify(expected, stored);
        return stored;
    }

    private void verify(Snapshot expected, Snapshot stored) {
        if (!expected.versionId().equals(stored.versionId()) || !expected.flowId().equals(stored.flowId())
                || !expected.projectId().equals(stored.projectId()) || !expected.portfolioId().equals(stored.portfolioId())
                || !expected.documentId().equals(stored.documentId()) || !expected.checksum().equals(stored.checksum())
                || !expected.checksum().equals(json.checksum(stored.rawJson()))) {
            throw error(HttpStatus.CONFLICT, "FLOW_INTEGRITY_ERROR", "Referências ou checksum do documento são incompatíveis.");
        }
    }

    public static String key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._:-]{8,100}")) {
            throw error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Informe Idempotency-Key com 8 a 100 caracteres ASCII válidos.");
        }
        return value;
    }

    static long revision(String value) {
        if (value == null) {
            throw error(HttpStatus.PRECONDITION_REQUIRED, "DRAFT_REVISION_REQUIRED", "Envie If-Match com o ETag do rascunho.");
        }
        if (!value.matches("\"[1-9][0-9]{0,17}\"")) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_DRAFT_ETAG", "ETag do rascunho inválido.");
        }
        return Long.parseLong(value.substring(1, value.length() - 1));
    }

    private static FlowException error(HttpStatus status, String code, String detail) {
        return new FlowException(status, code, detail);
    }
}
