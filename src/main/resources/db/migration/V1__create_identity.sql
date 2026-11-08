CREATE TABLE app_users (
    id VARCHAR(36) NOT NULL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    admin BOOLEAN NOT NULL,
    active BOOLEAN NOT NULL,
    auth_version BIGINT NOT NULL,
    row_version BIGINT NOT NULL,
    CONSTRAINT uk_app_users_email UNIQUE (email)
);

-- Serializes account mutations, including bootstrap and last-admin checks.
CREATE TABLE identity_guard (
    id INTEGER NOT NULL PRIMARY KEY
);
INSERT INTO identity_guard (id) VALUES (1);
