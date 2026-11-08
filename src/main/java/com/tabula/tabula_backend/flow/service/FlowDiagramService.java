package com.tabula.tabula_backend.flow.service;

import com.tabula.tabula_backend.flow.dto.FlowDiagramDtos.View;
import com.tabula.tabula_backend.flow.dto.FlowLayoutDtos;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import org.springframework.stereotype.Service;

@Service
public class FlowDiagramService {
    private final FlowService flows;
    private final FlowMetadataService metadata;
    private final FlowLayoutService layouts;
    private final FlowDiagramProjector projector;

    public FlowDiagramService(FlowService flows, FlowMetadataService metadata, FlowLayoutService layouts, FlowDiagramProjector projector) {
        this.flows = flows;
        this.metadata = metadata;
        this.layouts = layouts;
        this.projector = projector;
    }

    public View draft(AccountPrincipal actor, String flowId) {
        var draft = flows.draft(actor, flowId);
        return projector.project(flowId, "DRAFT", null, draft.revision(), draft.rawJson(), layout(actor, flowId));
    }

    public View version(AccountPrincipal actor, String versionId) {
        var version = metadata.version(actor, versionId);
        var raw = flows.export(actor, versionId);
        return projector.project(version.flowId(), "VERSION", versionId, null, raw, layout(actor, version.flowId()));
    }

    private FlowLayoutDtos.View layout(AccountPrincipal actor, String flowId) {
        try {
            return layouts.find(actor, flowId);
        } catch (FlowException ex) {
            if ("LAYOUT_NOT_FOUND".equals(ex.code())) {
                return null;
            }
            throw ex;
        }
    }
}
