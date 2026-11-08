package com.tabula.tabula_backend.flow.service;

import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import com.tabula.tabula_backend.flow.model.*;
import com.tabula.tabula_backend.flow.repository.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;

import java.util.Optional;

import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * SQL-only transactions. MongoDB and IBM are accessed by the orchestrator after these return.
 */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class FlowMetadataService {
    private final FlowRepository flows;
    private final FlowVersionRepository versions;
    private final FlowWriteRepository outbox;
    private final PortfolioService portfolios;

    public FlowMetadataService(FlowRepository flows, FlowVersionRepository versions, FlowWriteRepository outbox, PortfolioService portfolios) {
        this.flows = flows;
        this.versions = versions;
        this.outbox = outbox;
        this.portfolios = portfolios;
    }

    public Optional<VersionView> existing(AccountPrincipal actor, String projectId, String key, String hash) {
        portfolios.requireProjectEditing(actor, projectId);
        return versions.findByProjectIdAndRequestKey(projectId, key).map(v -> {
            if (!v.getRequestHash().equals(hash)) {
                throw error(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "Chave de idempotência reutilizada com outra requisição.");
            }
            return VersionView.from(v);
        });
    }

    public Reservation reserve(AccountPrincipal actor, String projectId, String flowId, String name, FlowType type,
                               String key, String requestHash, String checksum, String rawJson, String source, String targetId) {
        var project = portfolios.requireProjectEditing(actor, projectId);
        var replay = existing(actor, projectId, key, requestHash);
        if (replay.isPresent()) {
            return new Reservation(replay.get(), null);
        }
        Flow flow;
        if (flowId == null) {
            flow = flows.saveAndFlush(new Flow(projectId, name(name), type.name()));
        } else {
            flow = flows.findById(flowId).orElseThrow(FlowMetadataService::notFound);
            if (!flow.getProjectId().equals(projectId)) {
                throw notFound();
            }
            if (flow.isArchived()) {
                throw archived();
            }
            if (!flow.getType().equals(type.name())) {
                throw error(HttpStatus.CONFLICT, "FLOW_TYPE_MISMATCH", "Tipo da versão difere do fluxo.");
            }
        }
        var v = versions.saveAndFlush(new FlowVersion(flow, versions.lastNumber(flow.getId()) + 1, checksum, source, targetId, actor.id(), key, requestHash));
        outbox.save(new FlowWrite(v.getId()));
        return new Reservation(VersionView.from(v), new Snapshot(v.getDocumentId(), v.getId(), flow.getId(), projectId, project.portfolioId(), checksum, rawJson));
    }

    @Transactional(readOnly = true)
    public View find(AccountPrincipal actor, String flowId) {
        return View.from(flow(actor, flowId, false));
    }

    public View editable(AccountPrincipal actor, String flowId) {
        return View.from(flow(actor, flowId, true));
    }

    @Transactional(readOnly = true)
    public PageView<View> list(AccountPrincipal actor, String projectId, boolean includeArchived, int page, int size) {
        portfolios.project(actor, projectId);
        return PageView.from(flows.findInProject(projectId, includeArchived, paging(page, size)).map(View::from));
    }

    public View rename(AccountPrincipal actor, String id, String name) {
        var f = flow(actor, id, true);
        f.rename(name(name));
        return View.from(f);
    }

    public void archive(AccountPrincipal actor, String id) {
        flow(actor, id, true).archive();
    }

    @Transactional(readOnly = true)
    public VersionView version(AccountPrincipal actor, String id) {
        var v = versions.findById(id).orElseThrow(FlowMetadataService::notFound);
        portfolios.project(actor, v.getProjectId());
        return VersionView.from(v);
    }

    @Transactional(readOnly = true)
    public PageView<VersionView> versions(AccountPrincipal actor, String flowId, int page, int size) {
        flow(actor, flowId, false);
        return PageView.from(versions.findByFlowId(flowId, paging(page, size)).map(VersionView::from));
    }

    public Snapshot expected(AccountPrincipal actor, String versionId, boolean editing) {
        var v = versions.findById(versionId).orElseThrow(FlowMetadataService::notFound);
        var project = editing ? portfolios.requireProjectEditing(actor, v.getProjectId()) : portfolios.project(actor, v.getProjectId());
        if (editing) {
            flow(actor, v.getFlowId(), true);
        }
        return new Snapshot(v.getDocumentId(), v.getId(), v.getFlowId(), v.getProjectId(), project.portfolioId(), v.getChecksum(), null);
    }

    public VersionView complete(String id) {
        var v = versions.findLocked(id).orElseThrow(FlowMetadataService::notFound);
        v.ready();
        outbox.findById(id).orElseThrow(FlowMetadataService::notFound).finish(true);
        return VersionView.from(v);
    }

    public void fail(String id, String code) {
        var v = versions.findLocked(id).orElseThrow(FlowMetadataService::notFound);
        if (!"READY".equals(v.getState())) {
            v.fail(code);
            outbox.findById(id).orElseThrow(FlowMetadataService::notFound).finish(false);
        }
    }

    @Transactional(readOnly = true)
    public boolean leaseExpired(String id) {
        return outbox.findById(id).orElseThrow(FlowMetadataService::notFound).getLeaseUntil() <= System.currentTimeMillis();
    }

    private Flow flow(AccountPrincipal actor, String id, boolean edit) {
        String projectId = flows.findProjectId(id).orElseThrow(FlowMetadataService::notFound);
        if (edit) {
            portfolios.requireProjectEditing(actor, projectId);
        } else {
            portfolios.project(actor, projectId);
        }
        var f = flows.findById(id).orElseThrow(FlowMetadataService::notFound);
        if (edit && f.isArchived()) {
            throw archived();
        }
        return f;
    }

    private static Pageable paging(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_PAGE", "Paginação inválida.");
        }
        return PageRequest.of(page, size, Sort.by("id"));
    }

    private static String name(String value) {
        if (value == null || value.isBlank() || value.length() > 120) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_FLOW_NAME", "Nome obrigatório com até 120 caracteres.");
        }
        return value.strip();
    }

    private static FlowException archived() {
        return error(HttpStatus.CONFLICT, "FLOW_ARCHIVED", "Fluxo arquivado não permite alterações.");
    }

    private static FlowException notFound() {
        return error(HttpStatus.NOT_FOUND, "FLOW_NOT_FOUND", "Fluxo ou versão não encontrado.");
    }

    private static FlowException error(HttpStatus status, String code, String detail) {
        return new FlowException(status, code, detail);
    }
}
