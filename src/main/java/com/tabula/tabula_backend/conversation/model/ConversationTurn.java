package com.tabula.tabula_backend.conversation.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "conversation_turns")
public class ConversationTurn {
    @Id @Column(length = 36) private String id;
    @Column(name = "conversation_id", nullable = false, length = 36) private String conversationId;
    @Column(name = "turn_sequence", nullable = false) private long sequence;
    @Column(nullable = false, length = 16) private String state;
    @Column(name = "request_key", nullable = false, length = 100) private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "response_hash", length = 64) private String responseHash;
    @Column(name = "created_at", nullable = false, length = 35) private String createdAt;
    @Column(name = "lease_until", nullable = false) private long leaseUntil;
    @Column(name = "error_code", length = 64) private String errorCode;
    protected ConversationTurn() { }
    public ConversationTurn(Conversation conversation, String key, String hash) {
        id = UUID.randomUUID().toString(); conversationId = conversation.getId(); sequence = conversation.reserveTurn(id);
        requestKey = key; requestHash = hash; state = "RESERVED"; createdAt = Instant.now().toString(); renewLease();
    }
    public void sending() { state = "SENDING"; renewLease(); }
    public void complete(String hash) { state = "COMPLETED"; responseHash = hash; errorCode = null; }
    public void fail(String code, boolean unknown) { state = unknown ? "UNKNOWN" : "FAILED"; errorCode = code; }
    private void renewLease() { leaseUntil = System.currentTimeMillis() + 120_000; }
    public String getId() { return id; }
    public String getConversationId() { return conversationId; }
    public long getSequence() { return sequence; }
    public String getState() { return state; }
    public String getRequestHash() { return requestHash; }
    public String getResponseHash() { return responseHash; }
    public String getCreatedAt() { return createdAt; }
    public long getLeaseUntil() { return leaseUntil; }
    public String getErrorCode() { return errorCode; }
}
