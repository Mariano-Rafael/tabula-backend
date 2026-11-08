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
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static com.mongodb.client.model.Filters.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real local databases, simulated IBM only: never dispatches messages to configured assistants. */
@EnabledIfSystemProperty(named = "tabula.conversation.local.verify", matches = "true")
@SpringBootTest(properties = {"spring.config.location=file:src/main/resources/application.properties",
        "app.bootstrap-admin.enabled=false", "logging.level.root=ERROR", "spring.main.banner-mode=off"})
class LocalConversationTests {
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
    @MockitoBean IbmConversationGateway ibm;

    @Test
    void bothModesPersistTurnsAndContextWithRealDatabases() {
        var db = mongo.getDatabase(database);
        assertThat(((Number) db.runCommand(new Document("ping", 1)).get("ok")).intValue()).isEqualTo(1);
        System.out.println("CONVERSATION_LOCAL MONGODB_PING OK");
        var counters = new ConcurrentHashMap<String, Integer>();
        when(ibm.open(any())).thenReturn("synthetic-session");
        when(ibm.message(any(), nullable(String.class), anyString(), anyString(), anyMap())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            IbmConversationGateway.Binding b = call.getArgument(0); String id = call.getArgument(2);
            Map<String, Object> previous = call.getArgument(4);
            int counter = counters.merge(id, 1, Integer::sum);
            if ("V1".equals(b.mode())) {
                assertThat(previous.isEmpty() ? 0 : ((Number) previous.get("counter")).intValue()).isEqualTo(counter - 1);
            }
            return IbmConversationGateway.decode(Map.of("output", Map.of("generic", List.of(Map.of("response_type", "text", "text", "Synthetic reply"))),
                    "context", Map.of("counter", counter)), true);
        });
        var user = new AccountUser("Conversation local temporary admin", "conversation-" + UUID.randomUUID() + "@example.invalid", "unused", true);
        String portfolioId = null, projectId = null, integrationId = null, targetId = null;
        try {
            users.saveAndFlush(user); var actor = new AccountPrincipal(user.getId(), user.getAuthVersion());
            portfolioId = portfolios.create(actor, new PortfolioDtos.Create("Conversation local test", null)).id();
            projectId = portfolios.createProject(actor, portfolioId, new PortfolioDtos.Create("Temporary conversation project", null)).id();
            integrationId = integrations.create(actor, portfolioId, new CreateIntegration("Synthetic IBM binding")).id();
            targetId = integrations.createTarget(actor, projectId, new CreateTarget(UUID.fromString(integrationId),
                    new TargetConfig("Synthetic target", EnvironmentType.DRAFT, "synthetic-assistant", "synthetic-environment", "synthetic-workspace", null, null,
                            "2021-11-27", "2024-08-25"))).id();
            for (var mode : Mode.values()) {
                var c = service.open(actor, projectId, new Open(UUID.fromString(targetId), mode), UUID.randomUUID().toString());
                assertThat(c.state()).isEqualTo("ACTIVE");
                String key = UUID.randomUUID().toString();
                var first = service.message(actor, c.id(), new Message("Synthetic first message"), key);
                assertThat(first.state()).isEqualTo("COMPLETED");
                assertThat(service.message(actor, c.id(), new Message("Synthetic first message"), key).id()).isEqualTo(first.id());
                var second = service.message(actor, c.id(), new Message("Synthetic second message"), UUID.randomUUID().toString());
                assertThat(second.sequence()).isEqualTo(2);
                assertThat(history.messages(actor, c.id(), 0, 20).totalElements()).isEqualTo(2);
                var ticket = metadata.turn(actor, c.id(), first.id(), false);
                assertThatThrownBy(() -> documents.putResponse(ticket, "{}", ConversationJson.hash("{}")))
                        .isInstanceOf(ConversationException.class);
                assertThat(service.close(actor, c.id()).state()).isEqualTo("CLOSED");
                System.out.println("CONVERSATION_LOCAL " + mode.name() + " OPEN_MESSAGE_CONTEXT_HISTORY_CLOSE OK");
            }
            verify(ibm, times(4)).message(any(), nullable(String.class), anyString(), anyString(), anyMap());
            verify(ibm, times(1)).close(any(), org.mockito.ArgumentMatchers.eq("synthetic-session"));
        } finally {
            if (projectId != null) {
                for (var c : conversations.findByProjectId(projectId, PageRequest.of(0, 100))) {
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
        System.out.println("CONVERSATION_LOCAL TEMPORARY_RECORDS_CLEANED OK");
    }
}
