package com.tabula.tabula_backend.flow.service;

import com.tabula.tabula_backend.flow.dto.FlowDtos.FlowType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class FlowJsonCodec {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private final ObjectMapper json;
    public FlowJsonCodec(ObjectMapper json) { this.json = json; }
    public String encode(Object value) { return json.writeValueAsString(value); }
    public String validate(String raw, FlowType type) {
        Object parsed = parse(raw);
        if (!(parsed instanceof Map<?, ?> skill) || !(skill.get("workspace") instanceof Map<?, ?> workspace)
                || !(type == FlowType.DIALOG ? "dialog" : "action").equals(skill.get("type"))
                || !(workspace.get(type == FlowType.DIALOG ? "dialog_nodes" : "actions") instanceof List<?>)) {
            throw error("INVALID_FLOW_FORMAT", "Informe uma skill do tipo solicitado, com workspace e lista de nós/actions.");
        }
        return hash(encode(sorted(parsed, 0)));
    }
    public String requestHash(Object request) { return hash(encode(sorted(request, 0))); }
    public String checksum(String raw) { return hash(encode(sorted(parse(raw), 0))); }
    private Object parse(String raw) {
        if (raw == null || raw.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new FlowException(HttpStatus.PAYLOAD_TOO_LARGE, "FLOW_TOO_LARGE", "JSON do fluxo excede 4 MiB.");
        }
        try {
            return json.readerFor(Object.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(raw);
        } catch (RuntimeException ex) { throw error("INVALID_FLOW_JSON", "JSON do fluxo inválido."); }
    }
    private Object sorted(Object value, int depth) {
        if (depth > 64) { throw error("FLOW_TOO_DEEP", "JSON do fluxo excede a profundidade permitida."); }
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, item) -> result.put((String) key, sorted(item, depth + 1)));
            return result;
        }
        if (value instanceof List<?> list) { return list.stream().map(item -> sorted(item, depth + 1)).toList(); }
        return value;
    }
    private static String hash(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
    private static FlowException error(String code, String detail) { return new FlowException(HttpStatus.BAD_REQUEST, code, detail); }
}
