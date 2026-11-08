package com.tabula.tabula_backend.flow.dto;

import java.util.List;
import java.util.Map;

public final class FlowDiagramDtos {
    private FlowDiagramDtos() {
    }

    public record Diagnostic(String severity, String code, String sourceRef) {
    }

    public record Data(String label, String sourceId, String sourceRef, String kind, Object condition,
                       boolean editable, boolean layoutOnly, boolean collapsed, Map<String, Object> original) {
    }

    public record Node(String id, String type, FlowLayoutDtos.Position position, String parentId, Map<String, Double> style, Data data) {
    }

    public record EdgeData(String relation, String sourceRef, Object selector) {
    }

    public record Edge(String id, String source, String target, String type, String label, EdgeData data) {
    }

    public record View(int schemaVersion, String flowId, String source, String versionId, Long draftRevision,
                       String sourceChecksum, String type, boolean readOnly, boolean semanticComplete,
                       Long layoutRevision, FlowLayoutDtos.Viewport viewport, List<Node> nodes, List<Edge> edges,
                       List<Diagnostic> diagnostics) {
    }
}
