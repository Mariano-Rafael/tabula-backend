CREATE TABLE ibm_integrations (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    portfolio_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    active BOOLEAN NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT uk_ibm_integration_portfolio UNIQUE (portfolio_id),
    CONSTRAINT fk_ibm_integration_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolios(id)
);

CREATE TABLE ibm_targets (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    project_id VARCHAR(36) NOT NULL,
    integration_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    environment_type VARCHAR(20) NOT NULL,
    assistant_id VARCHAR(128),
    environment_id VARCHAR(128),
    dialog_workspace_id VARCHAR(128),
    dialog_skill_id VARCHAR(128),
    action_skill_id VARCHAR(128),
    v1_version VARCHAR(10) NOT NULL,
    v2_version VARCHAR(10) NOT NULL,
    active BOOLEAN NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT fk_ibm_target_project FOREIGN KEY (project_id) REFERENCES projects(id),
    CONSTRAINT fk_ibm_target_integration FOREIGN KEY (integration_id) REFERENCES ibm_integrations(id)
);
CREATE INDEX idx_ibm_target_project ON ibm_targets(project_id, active, id);
