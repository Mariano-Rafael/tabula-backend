package com.tabula.tabula_backend.history.service;

import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import com.tabula.tabula_backend.conversation.repository.ConversationDocumentStore;
import com.tabula.tabula_backend.conversation.service.*;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.integration.client.IbmConversationGateway;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.PageView;

import java.util.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class ConversationHistoryService {
    private final ConversationMetadataService metadata;
    private final ConversationDocumentStore documents;
    private final ConversationJson json;

    public ConversationHistoryService(ConversationMetadataService metadata, ConversationDocumentStore documents, ConversationJson json) {
        this.metadata = metadata;
        this.documents = documents;
        this.json = json;
    }

    public TurnView message(AccountPrincipal actor, String id, String turnId) {
        return view(metadata.turn(actor, id, turnId, false));
    }

    public PageView<TurnView> messages(AccountPrincipal actor, String id, int page, int size) {
        var conversation = metadata.read(actor, id);
        var source = metadata.turns(actor, id, page, size);
        return new PageView<>(source.content().stream().map(t -> view(new TurnTicket(conversation, t, false))).toList(),
                source.page(), source.size(), source.totalElements(), source.totalPages());
    }

    public Map<String, Object> previousContext(AccountPrincipal actor, Ticket conversation) {
        if (conversation.lastTurnId() == null || !"V1".equals(conversation.binding().mode())) {
            return Map.of();
        }
        var t = metadata.turn(actor, conversation.view().id(), conversation.lastTurnId(), false);
        var d = required(t);
        verifyResponse(t, d);
        return IbmConversationGateway.decode(json.response(d.responseJson()), true).context();
    }

    public Optional<TurnDocument> document(TurnTicket t) {
        var found = documents.find(t.conversation().portfolioId(), t.conversation().view().id(), t.turn().id());
        found.ifPresent(d -> verifyRequest(t, d));
        return found;
    }

    public void verifyResponse(TurnTicket t, TurnDocument d) {
        verifyRequest(t, d);
        if (d.responseJson() == null || d.responseHash() == null || !d.responseHash().equals(ConversationJson.hash(d.responseJson()))
                || (t.turn().responseHash() != null && !t.turn().responseHash().equals(d.responseHash()))) {
            throw integrity();
        }
    }

    private TurnView view(TurnTicket t) {
        var d = document(t);
        var output = List.<Map<String, Object>>of();
        if ("COMPLETED".equals(t.turn().state())) {
            var stored = d.orElseThrow(ConversationHistoryService::missing);
            verifyResponse(t, stored);
            output = IbmConversationGateway.decode(json.response(stored.responseJson()), "V1".equals(t.conversation().binding().mode())).output();
        }
        return new TurnView(t.turn().id(), t.turn().sequence(), t.turn().state(), d.map(TurnDocument::text).orElse(null), output, t.turn().createdAt(), t.turn().errorCode());
    }

    private TurnDocument required(TurnTicket t) {
        return document(t).orElseThrow(ConversationHistoryService::missing);
    }

    private static void verifyRequest(TurnTicket t, TurnDocument d) {
        if (!d.id().equals(t.turn().id()) || !d.conversationId().equals(t.conversation().view().id())
                || !d.projectId().equals(t.conversation().view().projectId()) || !d.portfolioId().equals(t.conversation().portfolioId())
                || d.sequence() != t.turn().sequence() || !d.requestHash().equals(t.turn().requestHash())
                || d.text() == null || !ConversationJson.hash(d.text()).equals(d.requestHash())) {
            throw integrity();
        }
    }

    private static ConversationException missing() {
        return new ConversationException(HttpStatus.SERVICE_UNAVAILABLE, "CONVERSATION_DOCUMENT_MISSING", "Documento de uma mensagem confirmada não foi encontrado.");
    }

    private static ConversationException integrity() {
        return new ConversationException(HttpStatus.CONFLICT, "CONVERSATION_INTEGRITY_ERROR", "Referências ou checksum da conversa são incompatíveis.");
    }
}
