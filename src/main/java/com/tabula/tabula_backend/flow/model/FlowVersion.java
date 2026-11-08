package com.tabula.tabula_backend.flow.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "flow_versions")
public class FlowVersion {
    @Id @Column(length = 36) private String id;
    @Column(name = "flow_id", nullable = false, length = 36) private String flowId;
    @Column(name = "project_id", nullable = false, length = 36) private String projectId;
    @Column(name = "version_number", nullable = false) private int number;
    @Column(name = "document_id", nullable = false, length = 36) private String documentId;
    @Column(nullable = false, length = 64) private String checksum;
    @Column(nullable = false, length = 16) private String state;
    @Column(name = "source_type", nullable = false, length = 16) private String sourceType;
    @Column(name = "source_target_id", length = 36) private String sourceTargetId;
    @Column(name = "author_id", nullable = false, length = 36) private String authorId;
    @Column(name = "created_at", nullable = false, length = 35) private String createdAt;
    @Column(name = "request_key", nullable = false, length = 100) private String requestKey;
    @Column(name = "request_hash", nullable = false, length = 64) private String requestHash;
    @Column(name = "error_code", length = 64) private String errorCode;
    protected FlowVersion() { }
    public FlowVersion(Flow flow, int number, String checksum, String source, String target, String author, String key, String hash) {
        id = UUID.randomUUID().toString(); documentId = UUID.randomUUID().toString(); flowId = flow.getId(); projectId = flow.getProjectId();
        this.number = number; this.checksum = checksum; state = "PENDING"; sourceType = source; sourceTargetId = target;
        authorId = author; requestKey = key; requestHash = hash; createdAt = Instant.now().toString();
    }
    public void ready() { state = "READY"; errorCode = null; }
    public void fail(String code) { state = "FAILED"; errorCode = code; }
    public String getId() { return id; }
    public String getFlowId() { return flowId; }
    public String getProjectId() { return projectId; }
    public int getNumber() { return number; }
    public String getDocumentId() { return documentId; }
    public String getChecksum() { return checksum; }
    public String getState() { return state; }
    public String getSourceType() { return sourceType; }
    public String getSourceTargetId() { return sourceTargetId; }
    public String getAuthorId() { return authorId; }
    public String getCreatedAt() { return createdAt; }
    public String getRequestHash() { return requestHash; }
    public String getErrorCode() { return errorCode; }
}
