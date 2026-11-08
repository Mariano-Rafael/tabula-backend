CREATE TABLE ibm_skill_writes (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    version_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    flow_type VARCHAR(8) NOT NULL,
    binding_json VARCHAR(2048) NOT NULL,
    resource_hash VARCHAR(64) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    baseline_hash VARCHAR(64) NOT NULL,
    candidate_hash VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL,
    error_code VARCHAR(64),
    created_at VARCHAR(35) NOT NULL,
    lease_until BIGINT NOT NULL,
    CONSTRAINT uk_ibm_write_request UNIQUE(project_id, request_key)
);
CREATE INDEX idx_ibm_write_project ON ibm_skill_writes(project_id, id);

-- Shared across projects/targets pointing to the same remote skill. Never expires automatically.
CREATE TABLE ibm_skill_write_locks (
    resource_hash VARCHAR(64) NOT NULL PRIMARY KEY,
    operation_id VARCHAR(36) NOT NULL
);
