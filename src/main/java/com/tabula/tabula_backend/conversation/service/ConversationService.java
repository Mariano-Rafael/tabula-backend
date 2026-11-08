package com.tabula.tabula_backend.conversation.service;

import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import com.tabula.tabula_backend.conversation.repository.ConversationDocumentStore;
import com.tabula.tabula_backend.history.service.ConversationHistoryService;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.client.IbmConversationGateway;
import com.tabula.tabula_backend.integration.service.IntegrationException;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConversationService {
    private static final Set<String> REJECTED = Set.of("IBM_AUTHENTICATION_FAILED", "IBM_PERMISSION_DENIED", "IBM_REQUEST_REJECTED",
            "IBM_RESOURCE_NOT_FOUND", "IBM_RATE_LIMITED", "IBM_OPERATION_NOT_SUPPORTED", "IBM_CONFIGURATION_INVALID", "IBM_CREDENTIAL_MISSING", "INVALID_IBM_IDENTIFIER",
            "IBM_CONNECTION_UNAVAILABLE", "IBM_CREDENTIAL_ENCRYPTION_UNAVAILABLE");
    private final ConversationMetadataService metadata;
    private final ConversationDocumentStore documents;
    private final ConversationHistoryService history;
    private final IbmConversationGateway ibm;
    private final ConversationJson json;

    public ConversationService(ConversationMetadataService metadata, ConversationDocumentStore documents,
                               ConversationHistoryService history, IbmConversationGateway ibm, ConversationJson json) {
        this.metadata = metadata;
        this.documents = documents;
        this.history = history;
        this.ibm = ibm;
        this.json = json;
    }

    public View open(AccountPrincipal actor, String projectId, Open input, String key) {
        var ticket = metadata.open(actor, projectId, input, ConversationJson.key(key));
        if (!ticket.created() || "V1".equals(ticket.binding().mode())) {
            return ticket.view();
        }
        try {
            return metadata.activate(ticket.view().id(), ibm.open(ticket.binding()));
        } catch (IntegrationException ex) {
            openingFailure(ticket.view().id(), ex.code(), !REJECTED.contains(ex.code()));
        } catch (RuntimeException ex) {
            openingFailure(ticket.view().id(), "IBM_OPEN_RESULT_UNKNOWN", true);
        }
        return metadata.read(actor, ticket.view().id()).view();
    }

    public TurnView message(AccountPrincipal actor, String conversationId, Message input, String key) {
        if (input.text() == null || input.text().isBlank() || input.text().length() > 8000) {
            throw new ConversationException(HttpStatus.BAD_REQUEST, "INVALID_MESSAGE", "Mensagem deve conter de 1 a 8000 caracteres.");
        }
        var t = metadata.reserveTurn(actor, conversationId, ConversationJson.key(key), ConversationJson.hash(input.text()));
        if (!t.created()) {
            return history.message(actor, conversationId, t.turn().id());
        }
        boolean sending = false;
        try {
            documents.putRequest(t, input.text());
            var context = history.previousContext(actor, t.conversation());
            metadata.markSending(t.turn().id());
            sending = true;
            var reply = ibm.message(t.conversation().binding(), t.conversation().sessionId(), conversationId, input.text(), context);
            String raw = json.encode(reply.raw());
            if (raw.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) {
                throw new ConversationException(HttpStatus.BAD_GATEWAY, "IBM_RESPONSE_TOO_LARGE", "Resposta de conversa acima do limite permitido.");
            }
            String hash = ConversationJson.hash(raw);
            documents.putResponse(t, raw, hash);
            metadata.completeTurn(t.turn().id(), hash);
        } catch (IntegrationException ex) {
            boolean expired = "V2".equals(t.conversation().binding().mode()) && "IBM_RESOURCE_NOT_FOUND".equals(ex.code());
            failed(t.turn().id(), expired ? "IBM_SESSION_UNAVAILABLE" : ex.code(), sending && !REJECTED.contains(ex.code()), expired);
        } catch (ConversationException ex) {
            failed(t.turn().id(), ex.code(), sending, false);
        } catch (RuntimeException ex) {
            failed(t.turn().id(), sending ? "IBM_RESULT_UNKNOWN" : "TURN_NOT_DISPATCHED", sending, false);
        }
        return history.message(actor, conversationId, t.turn().id());
    }

    public TurnView reconcileMessage(AccountPrincipal actor, String id, String turnId) {
        var t = metadata.turn(actor, id, turnId, true);
        var document = history.document(t);
        if (document.isPresent() && document.get().responseJson() != null) {
            history.verifyResponse(t, document.get());
            // Validate semantic response shape before advancing the conversation context pointer.
            IbmConversationGateway.decode(json.response(document.get().responseJson()), "V1".equals(t.conversation().binding().mode()));
            metadata.completeTurn(turnId, document.get().responseHash());
        } else {
            metadata.expireTurn(turnId);
        }
        return history.message(actor, id, turnId);
    }

    public View close(AccountPrincipal actor, String id) {
        var t = metadata.reserveClose(actor, id);
        if (!t.created()) {
            return t.view();
        }
        try {
            ibm.close(t.binding(), t.sessionId());
            return metadata.closed(id);
        } catch (IntegrationException ex) {
            closingFailure(id, ex.code());
        } catch (RuntimeException ex) {
            closingFailure(id, "IBM_CLOSE_RESULT_UNKNOWN");
        }
        return metadata.read(actor, id).view();
    }

    private void failed(String id, String code, boolean unknown, boolean expired) {
        try {
            metadata.failTurn(id, code, unknown, expired);
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private void openingFailure(String id, String code, boolean unknown) {
        try {
            metadata.openingFailure(id, code, unknown);
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private void closingFailure(String id, String code) {
        try {
            metadata.closingFailure(id, code);
        } catch (RuntimeException ex) {
            throw unavailable();
        }
    }

    private static ConversationException unavailable() {
        return new ConversationException(HttpStatus.SERVICE_UNAVAILABLE, "CONVERSATION_METADATA_UNAVAILABLE", "Confirmação indisponível. Consulte ou reconcilie a tentativa; não reenvie com uma nova chave.");
    }
}
