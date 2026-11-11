CREATE TABLE diagram_flows (
    flow_id VARCHAR(36) NOT NULL PRIMARY KEY,
    target_id VARCHAR(36),
    CONSTRAINT fk_diagram_flow FOREIGN KEY (flow_id) REFERENCES flows(id),
    CONSTRAINT fk_diagram_target FOREIGN KEY (target_id) REFERENCES ibm_targets(id)
);

CREATE TABLE diagram_versions (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    flow_id VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    version_number INTEGER NOT NULL,
    name VARCHAR(120) NOT NULL,
    revision BIGINT NOT NULL,
    document_id VARCHAR(36) NOT NULL,
    checksum VARCHAR(64) NOT NULL,
    author_id VARCHAR(36) NOT NULL,
    created_at VARCHAR(35) NOT NULL,
    request_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    CONSTRAINT fk_diagram_version_flow FOREIGN KEY (flow_id) REFERENCES diagram_flows(flow_id),
    CONSTRAINT uk_diagram_version_number UNIQUE(flow_id, version_number),
    CONSTRAINT uk_diagram_version_request UNIQUE(project_id, request_key)
);
