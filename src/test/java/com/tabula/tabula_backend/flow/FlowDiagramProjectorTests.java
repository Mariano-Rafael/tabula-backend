package com.tabula.tabula_backend.flow;

import com.tabula.tabula_backend.flow.dto.FlowDiagramDtos.*;
import com.tabula.tabula_backend.flow.dto.FlowLayoutDtos;
import com.tabula.tabula_backend.flow.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class FlowDiagramProjectorTests {
    private final JsonMapper json = JsonMapper.builder().build();
    private final FlowJsonCodec codec = new FlowJsonCodec(json);
    private final FlowDiagramProjector projector = new FlowDiagramProjector(json, codec);
    private View diagram(List<?> nodes) { return projector.project("flow", "DRAFT", null, 1L, codec.encode(Map.of("type", "dialog", "workspace", Map.of("dialog_nodes", nodes))), null); }
    private Map<String, Object> node(String id) { return Map.of("dialog_node", id, "type", "standard"); }
    @Test void projectsHierarchyEvaluationOrderAndExplicitJumpsWithStableIds() {
        var a = node("a"); var b = Map.of("dialog_node", "b", "previous_sibling", "a");
        var c = Map.of("dialog_node", "c", "parent", "a", "conditions", "#yes", "next_step", Map.of("behavior", "jump_to", "dialog_node", "b", "selector", "body"));
        var view = diagram(List.of(c, b, a)); var reordered = diagram(List.of(a, c, b));
        assertThat(view.nodes().stream().map(Node::id)).containsExactlyElementsOf(reordered.nodes().stream().map(Node::id).toList());
        assertThat(view.edges().stream().map(Edge::id)).containsExactlyInAnyOrderElementsOf(reordered.edges().stream().map(Edge::id).toList());
        assertThat(view.edges().stream().map(e -> e.data().relation())).containsExactlyInAnyOrder("PARENT", "EVALUATION_ORDER", "JUMP");
        assertThat(view.nodes().stream().filter(n -> "c".equals(n.data().sourceId())).findFirst().orElseThrow().data().sourceRef()).isEqualTo("/workspace/dialog_nodes/0");
        assertThat(view.readOnly()).isFalse(); assertThat(view.semanticComplete()).isFalse();
    }
    @Test void reportsAmbiguousReferencesAndCyclesWithoutDanglingEdges() {
        var view = diagram(List.of(Map.of("dialog_node", "a", "parent", "b"), Map.of("dialog_node", "b", "parent", "a"),
                Map.of("dialog_node", "c", "previous_sibling", "missing"), Map.of("dialog_node", "dup"), Map.of("dialog_node", "dup"),
                Map.of("dialog_node", "d", "next_step", Map.of("behavior", "jump_to", "dialog_node", "dup"))));
        assertThat(view.diagnostics().stream().map(Diagnostic::code)).contains("PARENT_CYCLE", "SOURCE_ID_DUPLICATED", "REFERENCE_MISSING_OR_AMBIGUOUS");
        var ids = view.nodes().stream().map(Node::id).toList();
        assertThat(new HashSet<>(ids)).hasSize(ids.size());
        for (var edge : view.edges()) { assertThat(ids).contains(edge.source(), edge.target()); }
    }
    @Test void overlaysVisualGroupsWithoutChangingIbmParentageAndOrdersParentsFirst() {
        var base = diagram(List.of(node("a"))); String id = base.nodes().getFirst().id();
        var layout = new FlowLayoutDtos.View("flow", 3, 1, List.of(
                new FlowLayoutDtos.Node(id, new FlowLayoutDtos.Position(42.0, 23.0), "group", null, null, true),
                new FlowLayoutDtos.Node("group", new FlowLayoutDtos.Position(10.0, 20.0), null, 600.0, 400.0, false),
                new FlowLayoutDtos.Node("old-id", new FlowLayoutDtos.Position(0.0, 0.0), null, null, null, false)),
                new FlowLayoutDtos.Viewport(4.0, 5.0, 1.2), "actor", "now");
        var view = projector.project("flow", "VERSION", "version", null, codec.encode(Map.of("type", "dialog", "workspace", Map.of("dialog_nodes", List.of(node("a"))))), layout);
        assertThat(view.layoutRevision()).isEqualTo(3);
        assertThat(view.nodes().getFirst().id()).isEqualTo("group");
        assertThat(view.nodes().get(1).position().x()).isEqualTo(42);
        assertThat(view.nodes().get(1).parentId()).isEqualTo("group");
        assertThat(view.edges()).isEmpty();
        assertThat(view.diagnostics().stream().map(Diagnostic::code)).contains("ORPHAN_LAYOUT_NODE");
    }
    @Test void unknownFieldsAndUnsupportedElementsRemainVisibleWithoutInventedTransitions() {
        var view = diagram(List.of(Map.of("dialog_node", "custom", "type", "new_type", "$unknown.field", Map.of("kept", true), "next_step", Map.of("behavior", "new_behavior")), Map.of("title", "no id"), 123));
        assertThat(view.nodes().getFirst().data().original()).isNotNull();
        assertThat(view.nodes().stream().filter(n -> "custom".equals(n.data().sourceId())).findFirst().orElseThrow().data().original()).containsKey("$unknown.field");
        assertThat(view.diagnostics().stream().map(Diagnostic::code)).contains("SOURCE_ID_MISSING", "INVALID_SOURCE_ELEMENT", "DIALOG_TYPE_UNSUPPORTED", "NEXT_STEP_NOT_PROJECTED");
        assertThat(view.edges()).isEmpty();
    }
    @Test void actionInternalsAreExplicitlyPartial() {
        var view = projector.project("flow", "VERSION", "version", null, "{\"type\":\"action\",\"workspace\":{\"actions\":[{\"action\":\"act1\",\"title\":\"Action\",\"steps\":[{\"step\":\"s1\"}]}]}}", null);
        assertThat(view.nodes()).hasSize(1); assertThat(view.nodes().getFirst().data().sourceId()).isEqualTo("act1");
        assertThat(view.edges()).isEmpty(); assertThat(view.diagnostics().stream().map(Diagnostic::code)).contains("ACTION_INTERNALS_NOT_PROJECTED");
    }
    @Test void emptyAndUnsupportedSourcesAreHandledExplicitly() {
        assertThat(diagram(List.of()).nodes()).isEmpty();
        assertThatThrownBy(() -> projector.project("flow", "DRAFT", null, 1L, "{\"type\":\"search\",\"workspace\":{}}", null)).isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> projector.project("flow", "DRAFT", null, 1L, "null", null)).isInstanceOf(FlowException.class);
    }
}
