CREATE TABLE conversations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(36) NOT NULL,
    api_mode VARCHAR(2) NOT NULL,
    binding_json VARCHAR(2048) NOT NULL,
    state VARCHAR(24) NOT NULL,
    remote_session_id VARCHAR(128),
    next_sequence BIGINT NOT NULL,
    active_turn_id VARCHAR(36),
    last_turn_id VARCHAR(36),
    lease_until BIGINT NOT NULL,
    open_key VARCHAR(100) NOT NULL,
    open_hash VARCHAR(64) NOT NULL,
    opened_at VARCHAR(35) NOT NULL,
    closed_at VARCHAR(35),
    error_code VARCHAR(64),
    CONSTRAINT uk_conversation_open UNIQUE(project_id, open_key),
    CONSTRAINT fk_conversation_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_conversation_target FOREIGN KEY (target_id) REFERENCES ibm_targets(id),
    CONSTRAINT fk_conversation_creator FOREIGN KEY (created_by) REFERENCES app_users(id)
);
CREATE INDEX idx_conversation_project ON conversations(project_id, id);

CREATE TABLE conversation_turns (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    turn_sequence BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_hash VARCHAR(64),
    created_at VARCHAR(35) NOT NULL,
    lease_until BIGINT NOT NULL,
    error_code VARCHAR(64),
    CONSTRAINT uk_turn_sequence UNIQUE(conversation_id, turn_sequence),
    CONSTRAINT uk_turn_request UNIQUE(conversation_id, request_key),
    CONSTRAINT fk_turn_conversation FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);
