package com.tabula.tabula_backend.flow.service;

import java.math.BigInteger;
import java.util.*;
import org.springframework.http.HttpStatus;

/** Structural validation only; node data and React Flow presentation fields remain intact. */
final class LocalCanvasValidator {
    private LocalCanvasValidator() { }

    static void validate(Object value) {
        var root = object(value);
        if (!root.keySet().containsAll(Set.of("schemaVersion", "nodes", "edges", "viewport"))
                || !Set.of("schemaVersion", "nodes", "edges", "viewport", "variables", "ibm").containsAll(root.keySet())
                || !BigInteger.ONE.equals(root.get("schemaVersion"))
                || !(root.get("nodes") instanceof List<?> nodes) || nodes.size() > 5000
                || !(root.get("edges") instanceof List<?> edges) || edges.size() > 20000) throw invalid();
        if(root.containsKey("variables")) object(root.get("variables"));
        if(root.containsKey("ibm")) object(root.get("ibm"));
        Map<String, Map<?, ?>> indexed = new HashMap<>();
        for (var item : nodes) {
            var node = object(item);
            String id = identifier(node.get("id"));
            if (indexed.put(id, node) != null) throw invalid();
            var position = object(node.get("position"));
            coordinate(position.get("x")); coordinate(position.get("y"));
            object(node.get("data"));
            if (node.containsKey("parentId")) identifier(node.get("parentId"));
        }
        Set<String> done = new HashSet<>();
        for (String start : indexed.keySet()) {
            Set<String> path = new HashSet<>();
            String current = start;
            while (current != null && !done.contains(current)) {
                if (!path.add(current) || !indexed.containsKey(current)) throw invalid();
                current = (String) indexed.get(current).get("parentId");
            }
            done.addAll(path);
        }
        Set<String> edgeIds = new HashSet<>();
        for (var item : edges) {
            var edge = object(item);
            if (!edgeIds.add(identifier(edge.get("id")))
                    || !indexed.containsKey(identifier(edge.get("source")))
                    || !indexed.containsKey(identifier(edge.get("target")))) throw invalid();
            for (String handle : List.of("sourceHandle", "targetHandle")) {
                if (edge.get(handle) != null) identifier(edge.get(handle));
            }
        }
        var viewport = object(root.get("viewport"));
        coordinate(viewport.get("x")); coordinate(viewport.get("y"));
        double zoom = number(viewport.get("zoom"));
        if (zoom < 0.01 || zoom > 100) throw invalid();
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        return map;
    }
    private static String identifier(Object value) {
        if (!(value instanceof String id) || id.isBlank() || id.length() > 1024
                || id.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        return id;
    }
    private static void coordinate(Object value) {
        if (Math.abs(number(value)) > 10_000_000) throw invalid();
    }
    private static double number(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw invalid();
        return n.doubleValue();
    }
    private static FlowException invalid() {
        return new FlowException(HttpStatus.BAD_REQUEST, "INVALID_LOCAL_CANVAS", "Documento do canvas local inválido.");
    }
}
