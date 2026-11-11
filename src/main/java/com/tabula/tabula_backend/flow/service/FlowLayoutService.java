package com.tabula.tabula_backend.flow.service;

import com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.*;
import com.tabula.tabula_backend.flow.repository.FlowLayoutStore;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;

import java.nio.charset.StandardCharsets;
import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

@Service
public class FlowLayoutService {
    private final FlowMetadataService metadata;
    private final PortfolioService portfolios;
    private final FlowLayoutStore store;
    private final FlowJsonCodec codec;
    private final ObjectMapper json;

    public FlowLayoutService(FlowMetadataService metadata, PortfolioService portfolios, FlowLayoutStore store, FlowJsonCodec codec, ObjectMapper json) {
        this.metadata = metadata;
        this.portfolios = portfolios;
        this.store = store;
        this.codec = codec;
        this.json = json;
    }

    public View find(AccountPrincipal actor, String flowId) {
        var flow = metadata.find(actor, flowId);
        if(flow.editorManaged()) throw error(HttpStatus.GONE,"VERSION_CANVAS_REQUIRED");
        requireIbm(flow.type());
        String portfolioId = portfolios.project(actor, flow.projectId()).portfolioId();
        var stored = store.find(portfolioId, flowId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "LAYOUT_NOT_FOUND"));
        return verified(stored, portfolioId, flowId);
    }

    public View save(AccountPrincipal actor, String flowId, String ifMatch, String ifNoneMatch, String raw) {
        var flow = metadata.editable(actor, flowId);
        if(flow.editorManaged()) throw error(HttpStatus.GONE,"VERSION_CANVAS_REQUIRED");
        requireIbm(flow.type());
        String portfolioId = portfolios.project(actor, flow.projectId()).portfolioId();
        Long expected = revision(ifMatch, ifNoneMatch);
        var input = parse(raw);
        String normalized = json.writeValueAsString(input);
        var saved = store.save(portfolioId, flowId, expected, normalized, codec.checksum(normalized), actor.id());
        return verified(saved, portfolioId, flowId);
    }

    private void requireIbm(com.tabula.tabula_backend.flow.dto.FlowDtos.FlowType type) {
        if (type == com.tabula.tabula_backend.flow.dto.FlowDtos.FlowType.LOCAL) {
            throw error(HttpStatus.UNPROCESSABLE_ENTITY, "LOCAL_CANVAS_USE_DRAFT");
        }
    }

    private View verified(Stored s, String portfolioId, String flowId) {
        if (!portfolioId.equals(s.portfolioId()) || !flowId.equals(s.flowId()) || !codec.checksum(s.rawJson()).equals(s.checksum()) || s.revision() < 1) {
            throw error(HttpStatus.CONFLICT, "LAYOUT_INTEGRITY_ERROR");
        }
        var payload = parse(s.rawJson());
        return new View(flowId, s.revision(), payload.schemaVersion(), payload.nodes(), payload.viewport(), s.updatedBy(), s.updatedAt());
    }

    private Save parse(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "LAYOUT_TOO_LARGE");
        }
        Save input;
        try {
            input = json.readerFor(Save.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(raw);
        } catch (RuntimeException ex) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_LAYOUT_JSON");
        }
        if (input == null || !Integer.valueOf(1).equals(input.schemaVersion()) || input.nodes() == null || input.nodes().size() > 5000) {
            throw invalid();
        }
        Map<String, Node> nodes = new HashMap<>();
        for (var node : input.nodes()) {
            if (node == null || !identifier(node.id()) || node.position() == null || !coordinate(node.position().x()) || !coordinate(node.position().y())
                    || !dimension(node.width()) || !dimension(node.height()) || (node.parentId() != null && !identifier(node.parentId()))
                    || (node.visualType() != null && (!node.id().matches("visual:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        || !Set.of("text", "generative", "options").contains(node.visualType())))
                    || nodes.put(node.id(), node) != null) {
                throw invalid();
            }
        }
        // Validate visual groups without recursion, including cycles of any length.
        Set<String> done = new HashSet<>();
        for (var node : input.nodes()) {
            Set<String> path = new HashSet<>();
            String id = node.id();
            while (id != null && !done.contains(id)) {
                if (!path.add(id) || !nodes.containsKey(id)) {
                    throw invalid();
                }
                id = nodes.get(id).parentId();
            }
            done.addAll(path);
        }
        var v = input.viewport();
        if (v != null && (!coordinate(v.x()) || !coordinate(v.y()) || !finite(v.zoom()) || v.zoom() < 0.01 || v.zoom() > 100)) {
            throw invalid();
        }
        return input;
    }

    private Long revision(String match, String noneMatch) {
        if (match != null && noneMatch != null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_LAYOUT_PRECONDITION");
        }
        if ("*".equals(noneMatch)) {
            return null;
        }
        if (noneMatch != null) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_LAYOUT_PRECONDITION");
        }
        if (match == null) {
            throw error(HttpStatus.PRECONDITION_REQUIRED, "LAYOUT_PRECONDITION_REQUIRED");
        }
        if (!match.matches("\"layout-[1-9][0-9]{0,17}\"")) {
            throw error(HttpStatus.BAD_REQUEST, "INVALID_LAYOUT_ETAG");
        }
        return Long.parseLong(match.substring(8, match.length() - 1));
    }

    private boolean identifier(String id) {
        return id != null && !id.isBlank() && id.length() <= 1024 && id.chars().noneMatch(Character::isISOControl);
    }

    private boolean finite(Double n) {
        return n != null && Double.isFinite(n);
    }

    private boolean coordinate(Double n) {
        return finite(n) && Math.abs(n) <= 10_000_000;
    }

    private boolean dimension(Double n) {
        return n == null || (finite(n) && n > 0 && n <= 1_000_000);
    }

    private static FlowException invalid() {
        return error(HttpStatus.BAD_REQUEST, "INVALID_LAYOUT");
    }

    private static FlowException error(HttpStatus status, String code) {
        return new FlowException(status, code, "Revise o layout visual e suas condições de revisão.");
    }
}
