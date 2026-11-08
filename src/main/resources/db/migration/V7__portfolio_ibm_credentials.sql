ALTER TABLE ibm_integrations ADD COLUMN credential_source VARCHAR(16) NOT NULL DEFAULT 'PROPERTIES';
ALTER TABLE ibm_integrations ADD COLUMN service_url VARCHAR(512);
ALTER TABLE ibm_integrations ADD COLUMN api_key_ciphertext VARCHAR(4096);
ALTER TABLE ibm_integrations ADD COLUMN credential_revision BIGINT NOT NULL DEFAULT 1;
