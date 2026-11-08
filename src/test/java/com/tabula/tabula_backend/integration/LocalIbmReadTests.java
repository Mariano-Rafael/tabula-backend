package com.tabula.tabula_backend.integration;

import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.integration.config.IbmSettings;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.repository.*;
import com.tabula.tabula_backend.integration.service.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Explicit local validation: IAM authentication plus GETs only on IBM; temporary local fixtures. */
@EnabledIfSystemProperty(named = "tabula.ibm.read.verify", matches = "true")
@SpringBootTest(properties = {
        "spring.config.location=file:src/main/resources/application.properties", "app.bootstrap-admin.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "logging.level.root=ERROR", "spring.main.banner-mode=off"
})
class LocalIbmReadTests {
    @Autowired IbmSettings settings;
    @Autowired IntegrationService integrations;
    @Autowired ConnectionTestService connections;
    @Autowired PortfolioService portfolios;
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired IbmIntegrationRepository integrationRepository;
    @Autowired IbmTargetRepository targetRepository;

    @Test
    void configuredIbmResourcesCanBeReadWithoutRemoteMutations() {
        String run = UUID.randomUUID().toString();
        var admin = new AccountUser("IBM read temporary admin", "ibm-read-" + run + "@example.invalid", "unused-in-this-test", true);
        String portfolioId = null, projectId = null, integrationId = null;
        try {
            users.saveAndFlush(admin);
            var actor = new AccountPrincipal(admin.getId(), admin.getAuthVersion());
            portfolioId = portfolios.create(actor, new PortfolioDtos.Create("IBM read " + run, null)).id();
            projectId = portfolios.createProject(actor, portfolioId, new PortfolioDtos.Create("Temporary IBM project", null)).id();
            integrationId = integrations.create(actor, portfolioId, new CreateIntegration("Configured IBM")).id();
            var connection = connections.integration(actor, integrationId);
            report("INSTANCE", connection);
            List<ProbeResult> results = new ArrayList<>();
            results.add(connection);
            for (var type : EnvironmentType.values()) {
                var config = settings.targetDefaults(type);
                String targetId = integrations.createTarget(actor, projectId,
                        new CreateTarget(UUID.fromString(integrationId), config)).id();
                var result = connections.target(actor, targetId);
                report(type.name(), result);
                results.add(result);
            }
            assertThat(connection.checks().stream().noneMatch(c -> "FAILED".equals(c.status())))
                    .as("Instance access has no unclassified failures; unsupported discovery is reported separately").isTrue();
            assertThat(results.subList(1, results.size()).stream().allMatch(ProbeResult::successful))
                    .as("All configured target reads pass; inspect sanitized check codes").isTrue();
        } finally {
            if (projectId != null) { targetRepository.deleteAll(targetRepository.findByProjectId(projectId, PageRequest.of(0, 100)).getContent()); }
            if (integrationId != null) { integrationRepository.deleteById(integrationId); }
            if (projectId != null) { projects.deleteById(projectId); }
            if (portfolioId != null) {
                members.deleteAll(members.findByPortfolioId(portfolioId, PageRequest.of(0, 100)).getContent());
                portfolioRepository.deleteById(portfolioId);
            }
            users.deleteById(admin.getId());
        }
        assertThat(users.existsById(admin.getId())).isFalse();
    }
    private static void report(String scope, ProbeResult result) {
        for (var check : result.checks()) {
            // Only fixed scope/resource/status/code strings: no IDs, names, URLs, tokens or raw IBM content.
            System.out.println("IBM_READ " + scope + " " + check.resource() + " " + check.status() + " " + check.code());
        }
    }
}
