package com.tabula.tabula_backend.integration.client;

import com.tabula.tabula_backend.integration.dto.IntegrationDtos.TargetConfig;
import com.tabula.tabula_backend.integration.service.IntegrationException;

import java.util.*;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Provider orchestration only: no local interpreter and no automatic message retries.
 */
@Component
public class IbmConversationGateway {
    public record Binding(String mode, String workspaceId, String assistantId, String environmentId, String version, IbmConnectionRef connection) {
        public Binding(String mode, String workspaceId, String assistantId, String environmentId, String version) {
            this(mode, workspaceId, assistantId, environmentId, version, null);
        }

        public Binding withConnection(IbmConnectionRef ref) {
            return new Binding(mode, workspaceId, assistantId, environmentId, version, ref);
        }
    }

    public record Reply(Map<String, Object> raw, Map<String, Object> context, List<Map<String, Object>> output) {
    }

    private final IbmHttpTransport http;
    private final IbmTokenProvider tokens;
    private final IbmClientFactory clients;

    public IbmConversationGateway(IbmHttpTransport http, IbmTokenProvider tokens) {
        this(http, tokens, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public IbmConversationGateway(IbmHttpTransport http, IbmTokenProvider tokens, IbmClientFactory clients) {
        this.http = http;
        this.tokens = tokens;
        this.clients = clients;
    }

    public static Binding bind(String mode, TargetConfig c) {
        if ("V1".equals(mode)) {
            if (c.dialogWorkspaceId() == null) {
                throw invalid("IBM_WORKSPACE_REQUIRED", "O modo v1 exige um workspaceId; skillId não é substituto.");
            }
            return new Binding(mode, IbmEndpointPolicy.identifier(c.dialogWorkspaceId()), null, null, c.v1Version());
        }
        if (!"V2".equals(mode) || c.assistantId() == null || c.environmentId() == null) {
            throw invalid("IBM_ENVIRONMENT_REQUIRED", "O modo v2 exige assistantId e environmentId.");
        }
        return new Binding(mode, null, IbmEndpointPolicy.identifier(c.assistantId()), IbmEndpointPolicy.identifier(c.environmentId()), c.v2Version());
    }

    public String open(Binding b) {
        if (!"V2".equals(b.mode())) {
            return null;
        }
        var client = client(b);
        var response = call(client.tokens(), () -> client.http().post(base(b) + "/sessions", b.version(), client.tokens().token(), Map.of()));
        if (!(response.get("session_id") instanceof String id) || !id.matches("[A-Za-z0-9_-]{1,128}")) {
            throw invalidReply();
        }
        return id;
    }

    public Reply message(Binding b, String sessionId, String conversationId, String text, Map<String, Object> previousContext) {
        var client = client(b);
        var http = client.http();
        var tokens = client.tokens();
        final Map<String, Object> response;
        if ("V1".equals(b.mode())) {
            response = call(tokens, () -> http.post("/v1/workspaces/" + IbmEndpointPolicy.identifier(b.workspaceId()) + "/message",
                    b.version(), tokens.token(), Map.of("input", Map.of("text", text), "context", previousContext, "user_id", conversationId)));
        } else {
            response = call(tokens, () -> http.post(base(b) + "/sessions/" + IbmEndpointPolicy.identifier(sessionId) + "/message",
                    b.version(), tokens.token(), Map.of("input", Map.of("message_type", "text", "text", text), "user_id", conversationId)));
        }
        return decode(response, "V1".equals(b.mode()));
    }

    public void close(Binding b, String sessionId) {
        if (!"V2".equals(b.mode()) || sessionId == null) {
            return;
        }
        try {
            var client = client(b);
            call(client.tokens(), () -> {
                client.http().delete(base(b) + "/sessions/" + IbmEndpointPolicy.identifier(sessionId), b.version(), client.tokens().token());
                return null;
            });
        } catch (IntegrationException ex) {
            if (!"IBM_RESOURCE_NOT_FOUND".equals(ex.code())) {
                throw ex;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Reply decode(Map<String, Object> response, boolean requireContext) {
        if (!(response.get("output") instanceof Map<?, ?> output) || (requireContext && !(response.get("context") instanceof Map<?, ?>))) {
            throw invalidReply();
        }
        if ((response.get("context") != null && !(response.get("context") instanceof Map<?, ?>))
                || (output.get("generic") != null && !(output.get("generic") instanceof List<?>))
                || (output.get("text") != null && !(output.get("text") instanceof List<?>))) {
            throw invalidReply();
        }
        Map<String, Object> context = response.get("context") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        List<Map<String, Object>> normalized = new ArrayList<>();
        if (output.get("generic") instanceof List<?> generic) {
            for (Object item : generic) {
                if (!(item instanceof Map<?, ?>)) {
                    throw invalidReply();
                }
                normalized.add((Map<String, Object>) item);
            }
        } else if (output.get("text") instanceof List<?> texts) {
            for (Object item : texts) {
                if (!(item instanceof String)) {
                    throw invalidReply();
                }
                normalized.add(Map.of("response_type", "text", "text", item));
            }
        }
        return new Reply(response, context, List.copyOf(normalized));
    }

    private IbmClientFactory.Clients client(Binding b) {
        return clients == null ? new IbmClientFactory.Clients(http, tokens) : clients.clients(b.connection());
    }

    private <T> T call(IbmTokenProvider tokens, Supplier<T> action) {
        try {
            return action.get();
        } catch (IntegrationException ex) {
            if ("IBM_AUTHENTICATION_FAILED".equals(ex.code())) {
                tokens.invalidate();
            }
            throw ex;
        }
    }

    private static String base(Binding b) {
        return "/v2/assistants/" + IbmEndpointPolicy.identifier(b.assistantId()) + "/environments/" + IbmEndpointPolicy.identifier(b.environmentId());
    }

    private static IntegrationException invalid(String code, String message) {
        return new IntegrationException(HttpStatus.UNPROCESSABLE_ENTITY, code, message);
    }

    private static IntegrationException invalidReply() {
        return new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_INVALID_MESSAGE_RESPONSE", "A IBM não retornou uma resposta válida de conversa.");
    }
}
