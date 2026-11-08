package com.tabula.tabula_backend.conversation;

import com.mongodb.client.MongoClient;
import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import com.tabula.tabula_backend.conversation.repository.*;
import com.tabula.tabula_backend.conversation.service.*;
import com.tabula.tabula_backend.history.service.ConversationHistoryService;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.integration.client.IbmConversationGateway;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.repository.*;
import com.tabula.tabula_backend.integration.service.IntegrationService;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.util.*;
import com.tabula.tabula_backend.integration.config.IbmSettings;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import static com.mongodb.client.model.Filters.*;
import static org.assertj.core.api.Assertions.*;

/** Opt-in: real databases and IBM Draft. Requires an explicitly chosen safe message. */
@EnabledIfSystemProperty(named = "tabula.conversation.ibm.verify", matches = "true")
@SpringBootTest(properties = {"spring.config.location=file:src/main/resources/application.properties",
        "app.bootstrap-admin.enabled=false", "logging.level.root=ERROR", "spring.main.banner-mode=off"})
class RealIbmConversationTests {
    @Autowired MongoClient mongo;
    @Value("${spring.mongodb.database}") String database;
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired IbmIntegrationRepository integrationRepository;
    @Autowired IbmTargetRepository targets;
    @Autowired PortfolioService portfolios;
    @Autowired IntegrationService integrations;
    @Autowired ConversationService service;
    @Autowired ConversationMetadataService metadata;
    @Autowired ConversationHistoryService history;
    @Autowired ConversationDocumentStore documents;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationTurnRepository turns;
    @Autowired IbmSettings settings;
    @Value("${IBM_TEST_MESSAGE:}") String testMessage;

    @Test
    void draftMessagePersistsHistoryAndClosesRealSession() {
        var db = mongo.getDatabase(database);
        assertThat(((Number) db.runCommand(new Document("ping", 1)).get("ok")).intValue()).isEqualTo(1);
        System.out.println("CONVERSATION_IBM_REAL MONGODB_PING OK");
        assertThat(testMessage).as("Set IBM_TEST_MESSAGE to an approved safe input").isNotBlank();
        var config = settings.targetDefaults(EnvironmentType.DRAFT);
        assertThat(config.assistantId()).isNotBlank();
        assertThat(config.environmentId()).isNotBlank();
        var user = new AccountUser("Conversation local temporary admin", "conversation-" + UUID.randomUUID() + "@example.invalid", "unused", true);
        String portfolioId = null, projectId = null, integrationId = null, targetId = null;
        try {
            users.saveAndFlush(user); var actor = new AccountPrincipal(user.getId(), user.getAuthVersion());
            portfolioId = portfolios.create(actor, new PortfolioDtos.Create("Conversation local test", null)).id();
            projectId = portfolios.createProject(actor, portfolioId, new PortfolioDtos.Create("Temporary conversation project", null)).id();
            integrationId = integrations.create(actor, portfolioId, new CreateIntegration("Real IBM Draft test")).id();
            targetId = integrations.createTarget(actor, projectId, new CreateTarget(UUID.fromString(integrationId),
                    config)).id();
            for (var mode : config.dialogWorkspaceId() == null ? List.of(Mode.V2) : List.of(Mode.V2, Mode.V1)) {
                var c = service.open(actor, projectId, new Open(UUID.fromString(targetId), mode), UUID.randomUUID().toString());
                assertThat(c.state()).isEqualTo("ACTIVE");
                String key = UUID.randomUUID().toString();
                var first = service.message(actor, c.id(), new Message(testMessage), key);
                assertThat(first.state()).isEqualTo("COMPLETED");
                assertThat(service.message(actor, c.id(), new Message(testMessage), key).id()).isEqualTo(first.id());
                assertThat(history.messages(actor, c.id(), 0, 20).totalElements()).isEqualTo(1);
                assertThat(service.close(actor, c.id()).state()).isEqualTo("CLOSED");
                System.out.println("CONVERSATION_IBM_REAL " + mode.name() + " OPEN_MESSAGE_IDEMPOTENCY_HISTORY_CLOSE OK");
            }
            if (config.dialogWorkspaceId() == null) System.out.println("CONVERSATION_IBM_REAL V1 SKIPPED_NO_WORKSPACE_ID");
        } finally {
            if (projectId != null) {
                for (var c : conversations.findByProjectId(projectId, PageRequest.of(0, 100))) {
                    try {
                        var closed = service.close(new AccountPrincipal(user.getId(), user.getAuthVersion()), c.getId());
                        assertThat(closed.state()).as("Real session cleanup").isEqualTo("CLOSED");
                    } catch (Exception cleanupFailure) {
                        throw new IllegalStateException("IBM session cleanup failed; inspect fixture metadata before deleting", cleanupFailure);
                    }
                    db.getCollection("conversation_documents").deleteMany(and(eq("portfolioId", portfolioId), eq("conversationId", c.getId())));
                    turns.deleteAll(turns.findByConversationId(c.getId(), PageRequest.of(0, 100)).getContent());
                    conversations.deleteById(c.getId());
                }
            }
            if (targetId != null) { targets.deleteById(targetId); }
            if (integrationId != null) { integrationRepository.deleteById(integrationId); }
            if (projectId != null) { projects.deleteById(projectId); }
            if (portfolioId != null) { members.deleteAll(members.findByPortfolioId(portfolioId, PageRequest.of(0, 100)).getContent()); portfolioRepository.deleteById(portfolioId); }
            users.deleteById(user.getId());
        }
        assertThat(users.existsById(user.getId())).isFalse();
        System.out.println("CONVERSATION_IBM_REAL TEMPORARY_RECORDS_CLEANED OK");
    }
}
