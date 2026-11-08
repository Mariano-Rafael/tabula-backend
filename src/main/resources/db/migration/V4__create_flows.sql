CREATE TABLE flows (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    flow_type VARCHAR(16) NOT NULL,
    archived BOOLEAN NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT fk_flow_project FOREIGN KEY (project_id) REFERENCES projects(id)
);
CREATE INDEX idx_flow_project ON flows(project_id, archived, id);

CREATE TABLE flow_versions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    flow_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    source_type VARCHAR(16) NOT NULL,
    source_target_id VARCHAR(36),
    author_id VARCHAR(36) NOT NULL,
    created_at VARCHAR(35) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    error_code VARCHAR(64),
    CONSTRAINT uk_flow_version UNIQUE(flow_id, version_number),
    CONSTRAINT uk_flow_document UNIQUE(document_id),
    CONSTRAINT uk_flow_request UNIQUE(project_id, request_key),
    CONSTRAINT fk_version_flow FOREIGN KEY (flow_id) REFERENCES flows(id),
    CONSTRAINT fk_version_author FOREIGN KEY (author_id) REFERENCES app_users(id)
);

CREATE TABLE flow_write_outbox (
    version_id VARCHAR(36) NOT NULL PRIMARY KEY,
    state VARCHAR(16) NOT NULL,
    lease_until BIGINT NOT NULL,
    CONSTRAINT fk_flow_write_version FOREIGN KEY (version_id) REFERENCES flow_versions(id)
);
