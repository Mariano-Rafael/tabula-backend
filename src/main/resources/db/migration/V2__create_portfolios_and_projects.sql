CREATE TABLE portfolios (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    archived BOOLEAN NOT NULL,
    row_version BIGINT NOT NULL
);

CREATE TABLE portfolio_members (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    portfolio_id VARCHAR(36) NOT NULL,
    user_id VARCHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    CONSTRAINT uk_portfolio_member UNIQUE (portfolio_id, user_id),
    CONSTRAINT fk_member_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolios(id),
    CONSTRAINT fk_member_user FOREIGN KEY (user_id) REFERENCES app_users(id)
);
CREATE INDEX idx_portfolio_member_user ON portfolio_members(user_id, portfolio_id);

CREATE TABLE projects (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    portfolio_id VARCHAR(36) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    archived BOOLEAN NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT fk_project_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolios(id)
);
CREATE INDEX idx_project_portfolio ON projects(portfolio_id, archived, id);
