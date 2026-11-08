package com.tabula.tabula_backend.integration;

import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.repository.IbmIntegrationRepository;
import com.tabula.tabula_backend.integration.service.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import static org.assertj.core.api.Assertions.*;

/** Real MySQL encryption/rotation using a fake API key. Never contacts IBM. */
@EnabledIfSystemProperty(named = "tabula.ibm.credentials.local.verify", matches = "true")
@SpringBootTest(properties = {"spring.config.location=file:src/main/resources/application.properties", "app.bootstrap-admin.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration", "logging.level.root=ERROR", "spring.main.banner-mode=off"})
class LocalIbmCredentialStorageTests {
    @Autowired UserRepository users;
    @Autowired PortfolioService portfolios;
    @Autowired PortfolioRepository portfolioRows;
    @Autowired PortfolioMemberRepository members;
    @Autowired IntegrationService integrations;
    @Autowired IbmIntegrationRepository integrationRows;
    @Autowired IbmCredentialCipher cipher;
    @Test void mysqlPersistsOnlyAuthenticatedCiphertextAndRotatesCredential() {
        var user = users.saveAndFlush(new AccountUser("Credential temporary owner", "credentials-"+UUID.randomUUID()+"@example.invalid", "unused", false));
        var actor = new AccountPrincipal(user.getId(), user.getAuthVersion());
        String portfolioId = null, integrationId = null;
        try {
            portfolioId = portfolios.create(actor, new PortfolioDtos.Create("Temporary credential storage", null)).id();
            integrationId = integrations.create(actor, portfolioId, new CreateIntegration("IBM fake storage test",
                    "https://api.us-south.assistant.watson.cloud.ibm.com/instances/test", "fake-storage-key")).id();
            var before = integrationRows.findById(integrationId).orElseThrow();
            assertThat(before.getApiKeyCiphertext()).doesNotContain("fake-storage-key");
            assertThat(cipher.decrypt(before.getApiKeyCiphertext(), before.credentialContext())).isEqualTo("fake-storage-key");
            var rotated = integrations.rotateCredential(actor, integrationId, new RotateCredential("fake-rotated-storage-key"));
            var after = integrationRows.findById(integrationId).orElseThrow();
            assertThat(rotated.credentialRevision()).isEqualTo(2);
            assertThat(after.getApiKeyCiphertext()).isNotEqualTo(before.getApiKeyCiphertext()).doesNotContain("fake-rotated-storage-key");
            assertThat(cipher.decrypt(after.getApiKeyCiphertext(), after.credentialContext())).isEqualTo("fake-rotated-storage-key");
            System.out.println("IBM_CREDENTIAL_LOCAL MYSQL_ENCRYPTION_ROTATION_OWNER OK");
        } finally {
            if (integrationId != null) integrationRows.deleteById(integrationId);
            if (portfolioId != null) { members.deleteAll(members.findByPortfolioId(portfolioId, PageRequest.of(0, 100)).getContent()); portfolioRows.deleteById(portfolioId); }
            users.deleteById(user.getId());
        }
        System.out.println("IBM_CREDENTIAL_LOCAL TEMPORARY_RECORDS_CLEANED OK");
    }
}
