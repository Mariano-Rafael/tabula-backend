package com.tabula.tabula_backend.flow.service;

import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

/** Applies a narrow command to a fresh copy of the complete draft; Mongo CAS commits all edits together. */
@Service
public class DialogNodeEditService {
    private final FlowMetadataService metadata;
    private final FlowService flows;
    private final ObjectMapper json;
    public DialogNodeEditService(FlowMetadataService metadata, FlowService flows, ObjectMapper json) {
        this.metadata = metadata; this.flows = flows; this.json = json;
    }
    public Draft edit(AccountPrincipal actor, String flowId, String ifMatch, String patchJson) {
        var flow = metadata.editable(actor, flowId);
        if (flow.type() != FlowType.DIALOG) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DIALOG_EDIT_REQUIRED");
        long expected = FlowService.revision(ifMatch);
        var patch = request(patchJson);
        String nodeId = string(patch.get("nodeId"), 1024, false);
        if (nodeId.isBlank()) throw invalid();
        var draft = flows.draft(actor, flowId);
        if (draft.revision() != expected) throw error(HttpStatus.PRECONDITION_FAILED, "DRAFT_REVISION_MISMATCH");
        var root = map(parse(draft.rawJson()), "DIALOG_SOURCE_INVALID");
        var workspace = map(root.get("workspace"), "DIALOG_SOURCE_INVALID");
        if (!"dialog".equals(root.get("type")) || !(workspace.get("dialog_nodes") instanceof List<?> nodes)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DIALOG_SOURCE_INVALID");
        var matches = nodes.stream().filter(Map.class::isInstance).map(Map.class::cast).filter(n -> nodeId.equals(n.get("dialog_node"))).toList();
        if (matches.isEmpty()) throw error(HttpStatus.NOT_FOUND, "DIALOG_NODE_NOT_FOUND");
        if (matches.size() != 1) throw error(HttpStatus.CONFLICT, "DIALOG_NODE_AMBIGUOUS");
        @SuppressWarnings("unchecked") Map<String, Object> node = matches.getFirst();
        if (!"standard".equals(node.getOrDefault("type", "standard"))) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "DIALOG_NODE_EDIT_UNSUPPORTED");
        if (patch.containsKey("title")) {
            String title = string(patch.get("title"), 512, false);
            if (title.isBlank() || !title.matches("[\\p{L}\\p{N} _.-]+")) throw invalid();
            node.put("title", title);
        }
        if (patch.containsKey("conditions")) {
            String condition = string(patch.get("conditions"), 2048, false);
            if (condition.isBlank()) throw invalid();
            node.put("conditions", condition);
        }
        if (patch.containsKey("textEdits")) editText(node, patch.get("textEdits"));
        return flows.updateDraft(actor, flowId, ifMatch, json.writeValueAsString(root));
    }
    private Map<String, Object> request(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > 128 * 1024) throw error(HttpStatus.PAYLOAD_TOO_LARGE, "DIALOG_EDIT_TOO_LARGE");
        var p = map(parse(raw), "INVALID_DIALOG_EDIT");
        if (!Set.of("nodeId", "title", "conditions", "textEdits").containsAll(p.keySet()) || !p.containsKey("nodeId")
                || p.size() < 2) throw invalid();
        return p;
    }
    private void editText(Map<String, Object> node, Object editsObject) {
        if (!(editsObject instanceof List<?> edits) || edits.isEmpty() || edits.size() > 100) throw invalid();
        var output = map(node.get("output"), "TEXT_RESPONSE_UNSUPPORTED");
        if (!(output.get("generic") instanceof List<?> generic)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "TEXT_RESPONSE_UNSUPPORTED");
        Set<String> touched = new HashSet<>();
        for (var raw : edits) {
            var edit = map(raw, "INVALID_DIALOG_EDIT");
            if (!edit.keySet().equals(Set.of("responseIndex", "valueIndex", "text"))) throw invalid();
            int responseIndex = index(edit.get("responseIndex")), valueIndex = index(edit.get("valueIndex"));
            if (!touched.add(responseIndex+":"+valueIndex)) throw error(HttpStatus.BAD_REQUEST, "DUPLICATE_TEXT_EDIT");
            if (responseIndex >= generic.size()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "TEXT_RESPONSE_INDEX_INVALID");
            var response = map(generic.get(responseIndex), "TEXT_RESPONSE_UNSUPPORTED");
            if (!"text".equals(response.get("response_type")) || !(response.get("values") instanceof List<?> values)) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "TEXT_RESPONSE_UNSUPPORTED");
            if (valueIndex >= values.size()) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "TEXT_RESPONSE_INDEX_INVALID");
            var value = map(values.get(valueIndex), "TEXT_RESPONSE_UNSUPPORTED");
            if (!(value.get("text") instanceof String) || value.containsKey("text_expression")) throw error(HttpStatus.UNPROCESSABLE_ENTITY, "TEXT_RESPONSE_UNSUPPORTED");
            value.put("text", string(edit.get("text"), 10_000, true));
        }
    }
    private int index(Object raw) {
        if (!(raw instanceof BigInteger number) || number.signum() < 0 || number.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) throw invalid();
        return number.intValue();
    }
    private String string(Object raw, int maximum, boolean multiline) {
        if (!(raw instanceof String value) || value.length() > maximum
                || value.chars().anyMatch(c -> Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t')))) throw invalid();
        return value;
    }
    private Object parse(String raw) {
        try { return json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(raw); }
        catch (RuntimeException ex) { throw invalid(); }
    }
    @SuppressWarnings("unchecked") private Map<String, Object> map(Object value, String code) {
        if (!(value instanceof Map<?, ?>)) throw error("INVALID_DIALOG_EDIT".equals(code) ? HttpStatus.BAD_REQUEST : HttpStatus.UNPROCESSABLE_ENTITY, code);
        return (Map<String, Object>) value;
    }
    private FlowException invalid() { return error(HttpStatus.BAD_REQUEST, "INVALID_DIALOG_EDIT"); }
    private FlowException error(HttpStatus status, String code) { return new FlowException(status, code, "Revise o nó, os campos solicitados e a revisão do rascunho."); }
}
