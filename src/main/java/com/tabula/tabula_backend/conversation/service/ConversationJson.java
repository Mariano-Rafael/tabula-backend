package com.tabula.tabula_backend.conversation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class ConversationJson {
    private final ObjectMapper json;

    public ConversationJson(ObjectMapper json) {
        this.json = json;
    }

    public String encode(Object value) {
        return json.writeValueAsString(value);
    }

    public <T> T decode(String raw, Class<T> type) {
        return json.readValue(raw, type);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> response(String raw) {
        try {
            return (Map<String, Object>) json.readerFor(Map.class).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                    DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(raw);
        } catch (RuntimeException ex) {
            throw new ConversationException(HttpStatus.CONFLICT, "CONVERSATION_DOCUMENT_INVALID", "Documento de conversa inválido.");
        }
    }

    public static String hash(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    public static String key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._:-]{8,100}")) {
            throw new ConversationException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Informe Idempotency-Key válido com 8 a 100 caracteres.");
        }
        return key;
    }
}
