package com.tabula.tabula_backend.portfolio;

import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.*;
import com.tabula.tabula_backend.portfolio.model.PortfolioRole;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: validates V2 on the configured MySQL and cleans up only this run's fixtures. */
@EnabledIfSystemProperty(named = "tabula.mysql.portfolio.verify", matches = "true")
@SpringBootTest(properties = {
        "spring.config.location=file:src/main/resources/application.properties",
        "app.bootstrap-admin.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "logging.level.root=ERROR", "spring.main.banner-mode=off"
})
class LocalMysqlPortfolioTests {
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolios;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired PortfolioService service;
    @Autowired DataSource dataSource;

    @Test
    void mysqlMigrationAndConcurrentOwnershipChangesAreSafe() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        String run = UUID.randomUUID().toString();
        var first = new AccountUser("Portfolio smoke temporary owner", "portfolio-smoke-a-" + run + "@example.invalid", "unused-in-this-test", false);
        var second = new AccountUser("Portfolio smoke temporary owner", "portfolio-smoke-b-" + run + "@example.invalid", "unused-in-this-test", false);
        String portfolioId = null;
        String projectId = null;
        try {
            users.saveAllAndFlush(List.of(first, second));
            portfolioId = service.create(principal(first), new Create("Portfolio smoke " + run, null)).id();
            projectId = service.createProject(principal(first), portfolioId, new Create("Temporary project", null)).id();
            service.addMember(principal(first), portfolioId, new AddMember(UUID.fromString(second.getId()), PortfolioRole.OWNER));
            String lockedPortfolioId = portfolioId;
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var results = new ArrayList<Future<String>>();
                for (var user : List.of(first, second)) {
                    results.add(executor.submit(() -> {
                        ready.countDown();
                        if (!start.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Start timeout"); }
                        try {
                            service.changeRole(principal(user), lockedPortfolioId, user.getId(), new ChangeRole(PortfolioRole.VIEWER));
                            return "UPDATED";
                        } catch (PortfolioException ex) { return ex.code(); }
                    }));
                }
                assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                        .containsExactlyInAnyOrder("UPDATED", "LAST_PORTFOLIO_OWNER");
            }
            assertThat(members.countByPortfolioIdAndRole(portfolioId, PortfolioRole.OWNER)).isEqualTo(1);
            AccountUser remainingOwner = members.findByPortfolioIdAndUserId(portfolioId, first.getId()).orElseThrow().getRole() == PortfolioRole.OWNER ? first : second;
            service.archive(principal(remainingOwner), portfolioId);
            assertThat(service.find(principal(remainingOwner), portfolioId).archived()).isTrue();
            assertThat(projects.existsById(projectId)).isTrue();
        } finally {
            if (projectId != null) { projects.deleteById(projectId); }
            if (portfolioId != null) {
                members.deleteAll(members.findByPortfolioId(portfolioId, PageRequest.of(0, 100)).getContent());
                portfolios.deleteById(portfolioId);
            }
            users.deleteById(second.getId());
            users.deleteById(first.getId());
        }
        assertThat(users.existsById(first.getId())).isFalse();
        assertThat(users.existsById(second.getId())).isFalse();
    }

    private static AccountPrincipal principal(AccountUser user) { return new AccountPrincipal(user.getId(), user.getAuthVersion()); }
}
