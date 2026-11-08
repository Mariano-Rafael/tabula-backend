package com.tabula.tabula_backend.flow.service;

import com.tabula.tabula_backend.flow.dto.FlowDiagramDtos.*;
import com.tabula.tabula_backend.flow.dto.FlowLayoutDtos;

import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.DeserializationFeature;

/**
 * Structural projection only. Never interprets expressions or regenerates IBM content.
 */
@Component
public class FlowDiagramProjector {
    private final ObjectMapper json;
    private final FlowJsonCodec codec;

    public FlowDiagramProjector(ObjectMapper json, FlowJsonCodec codec) {
        this.json = json;
        this.codec = codec;
    }

    @SuppressWarnings("unchecked")
    public View project(String flowId, String source, String versionId, Long draftRevision, String raw, FlowLayoutDtos.View layout) {
        String checksum = codec.checksum(raw);
        Object parsed = json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(raw);
        if (!(parsed instanceof Map<?, ?> root)) {
            throw invalid();
        }
        if (!(root.get("workspace") instanceof Map<?, ?> workspace)) {
            throw invalid();
        }
        boolean dialog = "dialog".equals(root.get("type"));
        if (!dialog && !"action".equals(root.get("type"))) {
            throw invalid();
        }
        Object rawItems = workspace.get(dialog ? "dialog_nodes" : "actions");
        if (!(rawItems instanceof List<?> items)) {
            throw invalid();
        }
        if (items.size() > 5000) {
            throw new FlowException(HttpStatus.PAYLOAD_TOO_LARGE, "DIAGRAM_TOO_LARGE", "O diagrama suporta até 5.000 elementos de origem nesta etapa.");
        }
        var diagnostics = new ArrayList<Diagnostic>();
        var nodes = new LinkedHashMap<String, Node>();
        var bySource = new HashMap<String, Node>();
        var duplicates = new HashSet<String>();
        for (int i = 0; i < items.size(); i++) {
            String ref = "/workspace/" + (dialog ? "dialog_nodes/" : "actions/") + i;
            if (!(items.get(i) instanceof Map<?, ?> item)) {
                diagnostics.add(new Diagnostic("ERROR", "INVALID_SOURCE_ELEMENT", ref));
                continue;
            }
            Map<String, Object> original = (Map<String, Object>) item;
            String sourceId = text(item.get(dialog ? "dialog_node" : "action"));
            String kind = dialog ? Objects.requireNonNullElse(text(item.get("type")), "standard") : "action";
            String id = (dialog ? "dialog:" : "action:") + codec.requestHash(sourceId == null ? Map.of("invalid", ref) : sourceId);
            if (sourceId == null) {
                diagnostics.add(new Diagnostic("ERROR", "SOURCE_ID_MISSING", ref));
            }
            if (sourceId != null && bySource.containsKey(sourceId)) {
                duplicates.add(sourceId);
                diagnostics.add(new Diagnostic("ERROR", "SOURCE_ID_DUPLICATED", ref));
                id = "unsupported:" + codec.requestHash(Map.of("ref", ref, "sourceId", sourceId));
            }
            String label = Objects.requireNonNullElse(text(item.get("title")), sourceId == null ? "Elemento sem ID" : sourceId);
            var data = new Data(label, sourceId, ref, kind, item.get("conditions"), false, false, false, original);
            var node = new Node(id, dialog ? "ibmDialog" : "ibmAction", new FlowLayoutDtos.Position(0.0, 0.0), null, Map.of(), data);
            nodes.put(id, node);
            if (sourceId != null) {
                bySource.putIfAbsent(sourceId, node);
            }
            if (dialog && !Set.of("standard", "folder", "frame", "slot", "response_condition", "event_handler").contains(kind)) {
                diagnostics.add(new Diagnostic("WARNING", "DIALOG_TYPE_UNSUPPORTED", ref + "/type"));
            }
            if (!dialog) {
                diagnostics.add(new Diagnostic("WARNING", "ACTION_INTERNALS_NOT_PROJECTED", ref));
            }
        }
        var edges = new ArrayList<Edge>();
        if (dialog) {
            Map<String, String> parents = new HashMap<>(), previous = new HashMap<>();
            Map<String, String> successor = new HashMap<>();
            for (var n : nodes.values()) {
                var d = n.data();
                if (d.sourceId() == null || duplicates.contains(d.sourceId())) {
                    continue;
                }
                var item = d.original();
                String parent = reference(item, "parent", d.sourceRef(), diagnostics);
                String sibling = reference(item, "previous_sibling", d.sourceRef(), diagnostics);
                if (parent != null) {
                    parents.put(d.sourceId(), parent);
                    relation("PARENT", parent, d.sourceId(), d.sourceRef() + "/parent", null, bySource, duplicates, edges, diagnostics);
                }
                if (sibling != null) {
                    previous.put(d.sourceId(), sibling);
                    var other = bySource.get(sibling);
                    if (other != null && !Objects.equals(item.get("parent"), other.data().original().get("parent"))) {
                        diagnostics.add(new Diagnostic("ERROR", "SIBLING_PARENT_MISMATCH", d.sourceRef() + "/previous_sibling"));
                    }
                    String key = Objects.toString(parent, "") + "\u0000" + sibling;
                    if (successor.putIfAbsent(key, d.sourceId()) != null) {
                        diagnostics.add(new Diagnostic("ERROR", "SIBLING_SUCCESSOR_DUPLICATED", d.sourceRef() + "/previous_sibling"));
                    }
                    relation("EVALUATION_ORDER", sibling, d.sourceId(), d.sourceRef() + "/previous_sibling", null, bySource, duplicates, edges, diagnostics);
                }
                if (item.get("next_step") instanceof Map<?, ?> next) {
                    String destination = text(next.get("dialog_node"));
                    if ("jump_to".equals(next.get("behavior")) && destination != null) {
                        relation("JUMP", d.sourceId(), destination,
                                d.sourceRef() + "/next_step", next.get("selector"), bySource, duplicates, edges, diagnostics);
                    } else {
                        diagnostics.add(new Diagnostic("WARNING", "NEXT_STEP_NOT_PROJECTED", d.sourceRef() + "/next_step"));
                    }
                } else if (item.get("next_step") != null) {
                    diagnostics.add(new Diagnostic("ERROR", "INVALID_NEXT_STEP", d.sourceRef() + "/next_step"));
                }
            }
            cycles(parents, "PARENT_CYCLE", bySource, diagnostics);
            cycles(previous, "SIBLING_CYCLE", bySource, diagnostics);
            Map<String, Integer> heads = new HashMap<>();
            for (var n : bySource.values()) {
                if (n.data().original().get("previous_sibling") == null) {
                    heads.merge(Objects.toString(n.data().original().get("parent"), ""), 1, Integer::sum);
                }
            }
            if (heads.values().stream().anyMatch(count -> count > 1)) {
                diagnostics.add(new Diagnostic("ERROR", "MULTIPLE_FIRST_SIBLINGS", "/workspace/dialog_nodes"));
            }
        }
        diagnostics.add(new Diagnostic("INFO", "STRUCTURAL_PROJECTION_ONLY", "/workspace"));
        if (dialog && "DRAFT".equals(source)) nodes.replaceAll((id, n) -> {
            var d = n.data();
            boolean editable = d.sourceId() != null && !duplicates.contains(d.sourceId()) && "standard".equals(d.original().getOrDefault("type", "standard"));
            return new Node(n.id(), n.type(), n.position(), n.parentId(), n.style(), new Data(d.label(), d.sourceId(), d.sourceRef(), d.kind(), d.condition(), editable, d.layoutOnly(), d.collapsed(), d.original()));
        });
        var positioned = overlay(nodes, layout, diagnostics);
        return new View(1, flowId, source, versionId, draftRevision, checksum, dialog ? "DIALOG" : "ACTION", positioned.stream().noneMatch(n -> n.data().editable()), false,
                layout == null ? null : layout.revision(), layout == null || layout.viewport() == null ? new FlowLayoutDtos.Viewport(0.0, 0.0, 1.0) : layout.viewport(),
                positioned, edges, diagnostics);
    }

    private List<Node> overlay(Map<String, Node> sourceNodes, FlowLayoutDtos.View layout, List<Diagnostic> diagnostics) {
        var visual = new HashMap<String, FlowLayoutDtos.Node>();
        if (layout != null) {
            for (var n : layout.nodes()) {
                visual.put(n.id(), n);
            }
        }
        var groups = new HashSet<String>();
        for (var n : visual.values()) {
            if (n.parentId() != null) {
                groups.add(n.parentId());
            }
        }
        for (var v : visual.values()) {
            if (!sourceNodes.containsKey(v.id())) {
                if (groups.contains(v.id())) {
                    sourceNodes.put(v.id(), new Node(v.id(), "group", v.position(), null, Map.of(),
                            new Data("Grupo visual", null, null, "visual_group", null, false, true, Boolean.TRUE.equals(v.collapsed()), Map.of())));
                } else {
                    diagnostics.add(new Diagnostic("WARNING", "ORPHAN_LAYOUT_NODE", "/layout/nodes/" + v.id()));
                }
            }
        }
        var result = new ArrayList<Node>();
        int index = 0;
        for (String id : sourceNodes.keySet().stream().sorted().toList()) {
            var n = sourceNodes.get(id);
            var v = visual.get(id);
            var position = v == null ? new FlowLayoutDtos.Position((index % 5) * 320.0, (index / 5) * 200.0) : v.position();
            index++;
            var style = new HashMap<String, Double>();
            if (v != null && v.width() != null) {
                style.put("width", v.width());
            }
            if (v != null && v.height() != null) {
                style.put("height", v.height());
            }
            var d = n.data();
            var data = new Data(d.label(), d.sourceId(), d.sourceRef(), d.kind(), d.condition(), d.editable(), d.layoutOnly(), v != null && Boolean.TRUE.equals(v.collapsed()), d.original());
            result.add(new Node(id, n.type(), position, v == null ? null : v.parentId(), style, data));
        }
        // React Flow requires visual parents before children. Layout is already validated as acyclic.
        var byId = new HashMap<String, Node>();
        result.forEach(n -> byId.put(n.id(), n));
        var ordered = new ArrayList<Node>();
        var done = new HashSet<String>();
        for (var n : result) {
            var chain = new ArrayList<Node>();
            Node cursor = n;
            while (cursor != null && !done.contains(cursor.id())) {
                chain.add(cursor);
                cursor = cursor.parentId() == null ? null : byId.get(cursor.parentId());
            }
            for (int i = chain.size() - 1; i >= 0; i--) {
                if (done.add(chain.get(i).id())) {
                    ordered.add(chain.get(i));
                }
            }
        }
        return ordered;
    }

    private void relation(String kind, String from, String to, String ref, Object selector, Map<String, Node> nodes,
                          Set<String> duplicates, List<Edge> edges, List<Diagnostic> diagnostics) {
        if (!nodes.containsKey(from) || !nodes.containsKey(to) || duplicates.contains(from) || duplicates.contains(to)) {
            diagnostics.add(new Diagnostic("ERROR", "REFERENCE_MISSING_OR_AMBIGUOUS", ref));
            return;
        }
        if (from.equals(to) && !"JUMP".equals(kind)) {
            diagnostics.add(new Diagnostic("ERROR", "SELF_REFERENCE", ref));
        }
        String source = nodes.get(from).id(), target = nodes.get(to).id();
        edges.add(new Edge("edge:" + codec.requestHash(Map.of("kind", kind, "source", source, "target", target)), source, target, "default", kind, new EdgeData(kind, ref, selector)));
    }

    private void cycles(Map<String, String> links, String code, Map<String, Node> nodes, List<Diagnostic> diagnostics) {
        var done = new HashSet<String>();
        for (String start : links.keySet()) {
            var path = new HashSet<String>();
            String id = start;
            while (id != null && !done.contains(id) && path.add(id)) {
                id = links.get(id);
            }
            if (id != null && path.contains(id)) {
                diagnostics.add(new Diagnostic("ERROR", code, nodes.get(start).data().sourceRef()));
            }
            done.addAll(path);
        }
    }

    private String reference(Map<String, Object> node, String field, String ref, List<Diagnostic> diagnostics) {
        Object raw = node.get(field);
        String id = text(raw);
        if (raw != null && id == null) {
            diagnostics.add(new Diagnostic("ERROR", "INVALID_REFERENCE", ref + "/" + field));
        }
        return id;
    }

    private String text(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }

    private FlowException invalid() {
        return new FlowException(HttpStatus.BAD_REQUEST, "INVALID_DIAGRAM_SOURCE", "Documento de origem inválido para projeção.");
    }
}
