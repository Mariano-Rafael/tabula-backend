package com.tabula.tabula_backend.conversation.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "conversations")
public class Conversation {
    @Id
    @Column(length = 36)
    private String id;
    @Column(name = "project_id", nullable = false, length = 36)
    private String projectId;
    @Column(name = "target_id", nullable = false, length = 36)
    private String targetId;
    @Column(name = "created_by", nullable = false, length = 36)
    private String createdBy;
    @Column(name = "api_mode", nullable = false, length = 2)
    private String mode;
    @Column(name = "binding_json", nullable = false, length = 2048)
    private String bindingJson;
    @Column(nullable = false, length = 24)
    private String state;
    @Column(name = "remote_session_id", length = 128)
    private String sessionId;
    @Column(name = "next_sequence", nullable = false)
    private long nextSequence;
    @Column(name = "active_turn_id", length = 36)
    private String activeTurnId;
    @Column(name = "last_turn_id", length = 36)
    private String lastTurnId;
    @Column(name = "lease_until", nullable = false)
    private long leaseUntil;
    @Column(name = "open_key", nullable = false, length = 100)
    private String openKey;
    @Column(name = "open_hash", nullable = false, length = 64)
    private String openHash;
    @Column(name = "opened_at", nullable = false, length = 35)
    private String openedAt;
    @Column(name = "closed_at", length = 35)
    private String closedAt;
    @Column(name = "error_code", length = 64)
    private String errorCode;

    protected Conversation() {
    }

    public Conversation(String projectId, String targetId, String creator, String mode, String binding, String key, String hash) {
        id = UUID.randomUUID().toString();
        this.projectId = projectId;
        this.targetId = targetId;
        createdBy = creator;
        this.mode = mode;
        bindingJson = binding;
        openKey = key;
        openHash = hash;
        nextSequence = 1;
        state = "V1".equals(mode) ? "ACTIVE" : "OPENING";
        openedAt = Instant.now().toString();
        renewLease();
    }

    public void activate(String sessionId) {
        this.sessionId = sessionId;
        state = "ACTIVE";
        errorCode = null;
    }

    public void state(String state, String code) {
        this.state = state;
        this.errorCode = code;
    }

    public long reserveTurn(String turnId) {
        activeTurnId = turnId;
        return nextSequence++;
    }

    public void finishTurn(String turnId, boolean complete) {
        if (complete) {
            lastTurnId = turnId;
        }
        if (turnId.equals(activeTurnId)) {
            activeTurnId = null;
            if ("BLOCKED".equals(state)) {
                state = "ACTIVE";
                errorCode = null;
            }
        }
    }

    public void renewLease() {
        leaseUntil = System.currentTimeMillis() + 120_000;
    }

    public void closed() {
        state = "CLOSED";
        closedAt = Instant.now().toString();
    }

    public String getId() {
        return id;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getMode() {
        return mode;
    }

    public String getBindingJson() {
        return bindingJson;
    }

    public String getState() {
        return state;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getActiveTurnId() {
        return activeTurnId;
    }

    public String getLastTurnId() {
        return lastTurnId;
    }

    public long getLeaseUntil() {
        return leaseUntil;
    }

    public String getOpenHash() {
        return openHash;
    }

    public String getOpenedAt() {
        return openedAt;
    }

    public String getClosedAt() {
        return closedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
