package com.tabula.tabula_backend.flow.dto;

import java.util.List;

/**
 * Visual metadata only; conversation semantics remain in the IBM JSON draft.
 */
public final class FlowLayoutDtos {
    private FlowLayoutDtos() {
    }

    public record Position(Double x, Double y) {
    }

    public record Node(String id, Position position, String parentId, Double width, Double height, Boolean collapsed, String visualType) {
        public Node(String id, Position position, String parentId, Double width, Double height, Boolean collapsed) {
            this(id, position, parentId, width, height, collapsed, null);
        }
    }

    public record Viewport(Double x, Double y, Double zoom) {
    }

    public record Save(Integer schemaVersion, List<Node> nodes, Viewport viewport) {
    }

    public record View(String flowId, long revision, int schemaVersion, List<Node> nodes, Viewport viewport,
                       String updatedBy, String updatedAt) {
    }

    public record Stored(String flowId, String portfolioId, long revision, String rawJson, String checksum,
                         String updatedBy, String updatedAt) {
    }
}
