package com.tabula.tabula_backend.conversation.repository;

import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import java.util.Optional;

public interface ConversationDocumentStore {
    void putRequest(TurnTicket ticket, String text);
    void putResponse(TurnTicket ticket, String rawJson, String responseHash);
    Optional<TurnDocument> find(String portfolioId, String conversationId, String turnId);
}
