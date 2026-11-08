package com.tabula.tabula_backend.conversation.dto;

import com.tabula.tabula_backend.conversation.model.*;
import com.tabula.tabula_backend.integration.client.IbmConversationGateway.Binding;
import jakarta.validation.constraints.*;

import java.util.*;

public final class ConversationDtos {
    private ConversationDtos() {
    }

    public enum Mode {V1, V2}

    public record Open(@NotNull UUID targetId, @NotNull Mode mode) {
    }

    public record Message(@NotBlank @Size(max = 8000) String text) {
    }

    public record View(String id, String projectId, String targetId, Mode mode, String state, String createdBy,
                       String openedAt, String closedAt, String errorCode) {
        public static View from(Conversation c) {
            return new View(c.getId(), c.getProjectId(), c.getTargetId(), Mode.valueOf(c.getMode()), c.getState(),
                    c.getCreatedBy(), c.getOpenedAt(), c.getClosedAt(), c.getErrorCode());
        }
    }

    public record TurnView(String id, long sequence, String state, String text, List<Map<String, Object>> output, String createdAt, String errorCode) {
    }

    // Internal contracts: never return these from HTTP controllers (binding and session are private).
    public record Ticket(View view, String portfolioId, Binding binding, String sessionId, String lastTurnId, boolean created) {
    }

    public record TurnInfo(String id, String conversationId, long sequence, String state, String requestHash, String responseHash,
                           String createdAt, long leaseUntil, String errorCode) {
        public static TurnInfo from(ConversationTurn t) {
            return new TurnInfo(t.getId(), t.getConversationId(), t.getSequence(), t.getState(), t.getRequestHash(),
                    t.getResponseHash(), t.getCreatedAt(), t.getLeaseUntil(), t.getErrorCode());
        }
    }

    public record TurnTicket(Ticket conversation, TurnInfo turn, boolean created) {
    }

    public record TurnDocument(String id, String conversationId, String projectId, String portfolioId, long sequence,
                               String requestHash, String text, String responseJson, String responseHash) {
    }
}
