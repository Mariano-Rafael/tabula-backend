package com.tabula.tabula_backend.conversation;

import com.tabula.tabula_backend.conversation.dto.ConversationDtos.*;
import com.tabula.tabula_backend.conversation.repository.*;
import com.tabula.tabula_backend.conversation.service.*;
import com.tabula.tabula_backend.history.service.ConversationHistoryService;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.integration.client.IbmConversationGateway;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.repository.*;
import com.tabula.tabula_backend.integration.service.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos;
import com.tabula.tabula_backend.portfolio.model.PortfolioRole;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class ConversationModuleTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired IbmIntegrationRepository integrationRepository;
    @Autowired IbmTargetRepository targets;
    @Autowired ConversationRepository conversations;
    @Autowired ConversationTurnRepository turns;
    @Autowired PortfolioService portfolios;
    @Autowired IntegrationService integrations;
    @Autowired ConversationService service;
    @Autowired ConversationMetadataService metadata;
    @Autowired ConversationHistoryService history;
    @Autowired ConversationJson json;
    @MockitoBean ConversationDocumentStore documents;
    @MockitoBean IbmConversationGateway ibm;
    private final Map<String, TurnDocument> stored = new ConcurrentHashMap<>();
    private AccountUser owner, viewer, operator, stranger, admin;
    private String projectId, portfolioId, targetId;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        cleanup(); stored.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = user(false); viewer = user(false); operator = user(false); stranger = user(false); admin = user(true);
        portfolioId = portfolios.create(actor(owner), new PortfolioDtos.Create("Test", null)).id();
        portfolios.addMember(actor(owner), portfolioId, new PortfolioDtos.AddMember(UUID.fromString(viewer.getId()), PortfolioRole.VIEWER));
        portfolios.addMember(actor(owner), portfolioId, new PortfolioDtos.AddMember(UUID.fromString(operator.getId()), PortfolioRole.OPERATOR));
        projectId = portfolios.createProject(actor(owner), portfolioId, new PortfolioDtos.Create("Project", null)).id();
        String integration = integrations.create(actor(admin), portfolioId, new CreateIntegration("IBM")).id();
        targetId = integrations.createTarget(actor(owner), projectId, new CreateTarget(UUID.fromString(integration),
                new TargetConfig("Target", EnvironmentType.DRAFT, "assistant", "draft", "workspace", "dialog", "action", "2021-11-27", "2024-08-25"))).id();
        when(ibm.open(any())).thenAnswer(call -> { noTransaction(); return "remote-session"; });
        doAnswer(call -> { noTransaction(); return null; }).when(ibm).close(any(), nullable(String.class));
        when(ibm.message(any(), nullable(String.class), anyString(), anyString(), anyMap())).thenAnswer(call -> { noTransaction(); return reply(); });
        doAnswer(call -> {
            noTransaction(); TurnTicket t = call.getArgument(0); String text = call.getArgument(1);
            stored.putIfAbsent(t.turn().id(), new TurnDocument(t.turn().id(), t.conversation().view().id(), t.conversation().view().projectId(),
                    t.conversation().portfolioId(), t.turn().sequence(), t.turn().requestHash(), text, null, null)); return null;
        }).when(documents).putRequest(any(), anyString());
        doAnswer(call -> {
            noTransaction(); TurnTicket t = call.getArgument(0); var old = stored.get(t.turn().id());
            stored.put(t.turn().id(), new TurnDocument(old.id(), old.conversationId(), old.projectId(), old.portfolioId(), old.sequence(), old.requestHash(),
                    old.text(), call.getArgument(1), call.getArgument(2))); return null;
        }).when(documents).putResponse(any(), anyString(), anyString());
        when(documents.find(anyString(), anyString(), anyString())).thenAnswer(call -> {
            noTransaction(); var d = stored.get(call.getArgument(2));
            return d != null && d.portfolioId().equals(call.getArgument(0)) && d.conversationId().equals(call.getArgument(1)) ? Optional.of(d) : Optional.empty();
        });
    }
    @AfterEach
    void cleanup() {
        turns.deleteAll(); conversations.deleteAll(); targets.deleteAll(); integrationRepository.deleteAll();
        projects.deleteAll(); members.deleteAll(); portfolioRepository.deleteAll(); users.deleteAll();
    }
    @Test
    void v1ReusesConfirmedContextAndDoesNotCreateRemoteSession() throws Exception {
        var c = open(Mode.V1);
        var first = service.message(actor(owner), c.id(), new Message("Hello"), "message-first-key");
        var second = service.message(actor(owner), c.id(), new Message("Next"), "message-second-key");
        assertThat(first.state()).isEqualTo("COMPLETED"); assertThat(second.sequence()).isEqualTo(2);
        var contexts = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(ibm, times(2)).message(any(), isNull(), eq(c.id()), anyString(), contexts.capture());
        assertThat(contexts.getAllValues().get(0)).isEmpty();
        assertThat(((Number) contexts.getAllValues().get(1).get("counter")).intValue()).isEqualTo(1);
        verify(ibm, never()).open(any());
        assertThat(service.close(actor(owner), c.id()).state()).isEqualTo("CLOSED");
        verify(ibm, never()).close(any(), any());
        mvc.perform(get("/api/v1/conversations/" + c.id() + "/messages").with(as(viewer)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(content().string(not(containsString("server_secret"))));
    }
    @Test
    void v2OpeningAndMessageKeysAreIdempotentAndSessionIsNeverExposed() throws Exception {
        var input = new Open(UUID.fromString(targetId), Mode.V2);
        var c = service.open(actor(owner), projectId, input, "open-fixed-key");
        assertThat(service.open(actor(owner), projectId, input, "open-fixed-key").id()).isEqualTo(c.id());
        var first = service.message(actor(owner), c.id(), new Message("Hello"), "message-fixed-key");
        assertThat(service.message(actor(owner), c.id(), new Message("Hello"), "message-fixed-key").id()).isEqualTo(first.id());
        assertThatThrownBy(() -> service.message(actor(owner), c.id(), new Message("Different"), "message-fixed-key"))
                .isInstanceOfSatisfying(ConversationException.class, ex -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        verify(ibm, times(1)).open(any()); verify(ibm, times(1)).message(any(), eq("remote-session"), eq(c.id()), eq("Hello"), anyMap());
        mvc.perform(get("/api/v1/conversations/" + c.id()).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").doesNotExist()).andExpect(jsonPath("$.binding").doesNotExist())
                .andExpect(content().string(not(containsString("remote-session"))));
    }
    @Test
    void timeoutBlocksConversationAndDuplicateNeverResends() {
        var c = open(Mode.V2);
        when(ibm.message(any(), anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IntegrationException(HttpStatus.GATEWAY_TIMEOUT, "IBM_TIMEOUT", "Timeout"));
        var first = service.message(actor(owner), c.id(), new Message("Hello"), "unknown-message-key");
        assertThat(first.state()).isEqualTo("UNKNOWN");
        assertThat(metadata.read(actor(owner), c.id()).view().state()).isEqualTo("BLOCKED");
        assertThat(service.message(actor(owner), c.id(), new Message("Hello"), "unknown-message-key").id()).isEqualTo(first.id());
        assertThatThrownBy(() -> service.message(actor(owner), c.id(), new Message("Next"), "new-message-key")).isInstanceOf(ConversationException.class);
        assertThat(service.reconcileMessage(actor(owner), c.id(), first.id()).state()).isEqualTo("UNKNOWN");
        verify(ibm, times(1)).message(any(), anyString(), anyString(), anyString(), anyMap());
        assertThat(service.close(actor(owner), c.id()).state()).isEqualTo("CLOSED");
    }
    @Test
    void definiteRejectionAllowsNextMessageWithoutAdvancingContext() {
        var c = open(Mode.V1);
        when(ibm.message(any(), isNull(), anyString(), anyString(), anyMap()))
                .thenThrow(new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_REQUEST_REJECTED", "Rejected")).thenReturn(reply());
        assertThat(service.message(actor(owner), c.id(), new Message("Rejected"), "rejected-message-key").state()).isEqualTo("FAILED");
        assertThat(service.message(actor(owner), c.id(), new Message("Valid"), "accepted-message-key").state()).isEqualTo("COMPLETED");
        verify(ibm).message(any(), isNull(), eq(c.id()), eq("Valid"), eq(Map.of()));
    }
    @Test
    void expiredV2SessionIsNotAutomaticallyRecreated() {
        var c = open(Mode.V2);
        when(ibm.message(any(), anyString(), anyString(), anyString(), anyMap()))
                .thenThrow(new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_RESOURCE_NOT_FOUND", "Missing"));
        assertThat(service.message(actor(owner), c.id(), new Message("Hello"), "expired-message-key").state()).isEqualTo("FAILED");
        assertThat(metadata.read(actor(owner), c.id()).view().state()).isEqualTo("SESSION_EXPIRED");
        assertThatThrownBy(() -> service.message(actor(owner), c.id(), new Message("Again"), "another-message-key")).isInstanceOf(ConversationException.class);
        verify(ibm, times(1)).open(any());
    }
    @Test
    void mongoFailureBeforeDispatchNeverSendsMessage() {
        var c = open(Mode.V1);
        doThrow(storageFailure()).when(documents).putRequest(any(), anyString());
        var turn = service.message(actor(owner), c.id(), new Message("Hello"), "storage-before-key");
        assertThat(turn.state()).isEqualTo("FAILED"); assertThat(metadata.read(actor(owner), c.id()).view().state()).isEqualTo("ACTIVE");
        verify(ibm, never()).message(any(), any(), any(), any(), any());
    }
    @Test
    void durableReplyCanReconcileUnknownResultWithoutCallingIbmAgain() {
        var c = open(Mode.V1);
        doThrow(storageFailure()).when(documents).putResponse(any(), anyString(), anyString());
        var turn = service.message(actor(owner), c.id(), new Message("Hello"), "storage-after-key");
        assertThat(turn.state()).isEqualTo("UNKNOWN");
        var old = stored.get(turn.id()); String raw = json.encode(reply().raw());
        stored.put(turn.id(), new TurnDocument(old.id(), old.conversationId(), old.projectId(), old.portfolioId(), old.sequence(), old.requestHash(), old.text(), raw, ConversationJson.hash(raw)));
        assertThat(service.reconcileMessage(actor(owner), c.id(), turn.id()).state()).isEqualTo("COMPLETED");
        assertThat(metadata.read(actor(owner), c.id()).view().state()).isEqualTo("ACTIVE");
        verify(ibm, times(1)).message(any(), any(), any(), any(), any());
    }
    @Test
    void expiredReservedAndSendingAttemptsHaveDifferentRecovery() {
        var c = open(Mode.V1);
        var reserved = metadata.reserveTurn(actor(owner), c.id(), "reserved-message-key", ConversationJson.hash("Hello"));
        expire(reserved.turn().id());
        assertThat(service.reconcileMessage(actor(owner), c.id(), reserved.turn().id()).state()).isEqualTo("FAILED");
        var sending = metadata.reserveTurn(actor(owner), c.id(), "sending-message-key", ConversationJson.hash("Next"));
        metadata.markSending(sending.turn().id()); expire(sending.turn().id());
        assertThat(service.reconcileMessage(actor(owner), c.id(), sending.turn().id()).state()).isEqualTo("UNKNOWN");
        assertThat(metadata.read(actor(owner), c.id()).view().state()).isEqualTo("BLOCKED");
        verify(ibm, never()).message(any(), any(), any(), any(), any());
    }
    @Test
    void concurrentMessageIsBlockedWhileSameKeyOnlyReadsPendingAttempt() throws Exception {
        var c = open(Mode.V2); var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(ibm.message(any(), anyString(), anyString(), anyString(), anyMap())).thenAnswer(call -> {
            noTransaction(); entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) { throw new AssertionError("Release timeout"); } return reply();
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var future = executor.submit(() -> service.message(actor(owner), c.id(), new Message("Hello"), "concurrent-message-key"));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(service.message(actor(owner), c.id(), new Message("Hello"), "concurrent-message-key").state()).isEqualTo("SENDING");
                assertThatThrownBy(() -> service.message(actor(owner), c.id(), new Message("Other"), "other-concurrent-key"))
                        .isInstanceOfSatisfying(ConversationException.class, ex -> assertThat(ex.code()).isEqualTo("CONVERSATION_BUSY"));
            } finally { release.countDown(); }
            assertThat(future.get(10, TimeUnit.SECONDS).state()).isEqualTo("COMPLETED");
        }
        verify(ibm, times(1)).message(any(), anyString(), anyString(), anyString(), anyMap());
    }
    @Test
    void openingUncertaintyAndCloseRetriesNeverResendMessages() {
        when(ibm.open(any())).thenThrow(new IntegrationException(HttpStatus.GATEWAY_TIMEOUT, "IBM_TIMEOUT", "Timeout"));
        var input = new Open(UUID.fromString(targetId), Mode.V2);
        var c = service.open(actor(owner), projectId, input, "unknown-open-key");
        assertThat(c.state()).isEqualTo("OPEN_UNKNOWN");
        service.open(actor(owner), projectId, input, "unknown-open-key"); verify(ibm, times(1)).open(any());
        assertThat(service.close(actor(owner), c.id()).errorCode()).isEqualTo("REMOTE_SESSION_UNCONFIRMED");
        doReturn("remote-session").when(ibm).open(any());
        var another = open(Mode.V2);
        doThrow(new IntegrationException(HttpStatus.GATEWAY_TIMEOUT, "IBM_TIMEOUT", "Timeout")).doNothing().when(ibm).close(any(), anyString());
        assertThat(service.close(actor(owner), another.id()).state()).isEqualTo("CLOSE_UNKNOWN");
        assertThat(service.close(actor(owner), another.id()).state()).isEqualTo("CLOSED");
        service.close(actor(owner), another.id()); verify(ibm, times(2)).close(any(), anyString());
    }
    @Test
    void rolesScopeCsrfAndArchivedCleanupAreEnforced() throws Exception {
        var c = open(Mode.V2);
        mvc.perform(get("/api/v1/conversations/" + c.id()).with(as(stranger))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/conversations/" + c.id()).with(as(viewer))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/conversations/" + c.id() + "/messages").with(as(viewer)).with(csrf()).header("Idempotency-Key", "viewer-message-key")
                .contentType("application/json").content("{\"text\":\"Hello\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/conversations/" + c.id() + "/messages").with(as(operator)).header("Idempotency-Key", "operator-message-key")
                .contentType("application/json").content("{\"text\":\"Hello\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/conversations/" + c.id() + "/messages").with(as(operator)).with(csrf()).header("Idempotency-Key", "operator-message-key")
                .contentType("application/json").content("{\"text\":\"Hello\",\"context\":{\"admin\":true}}")).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("server_secret"))));
        portfolios.archiveProject(actor(owner), projectId);
        assertThatThrownBy(() -> service.message(actor(owner), c.id(), new Message("Again"), "archived-message-key")).isInstanceOf(RuntimeException.class);
        assertThat(service.close(actor(operator), c.id()).state()).isEqualTo("CLOSED");
        mvc.perform(get("/api/v1/conversations/" + c.id() + "/messages").with(as(viewer))).andExpect(status().isOk());
    }
    @Test
    void historyRejectsWrongConversationAndCorruptedContent() throws Exception {
        var c = open(Mode.V1); var another = open(Mode.V1);
        var turn = service.message(actor(owner), c.id(), new Message("Hello"), "history-message-key");
        mvc.perform(get("/api/v1/conversations/" + another.id() + "/messages/" + turn.id()).with(as(owner))).andExpect(status().isNotFound());
        var old = stored.get(turn.id());
        stored.put(turn.id(), new TurnDocument(old.id(), old.conversationId(), old.projectId(), old.portfolioId(), old.sequence(), old.requestHash(), "Tampered", old.responseJson(), old.responseHash()));
        mvc.perform(get("/api/v1/conversations/" + c.id() + "/messages").with(as(owner))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_INTEGRITY_ERROR"));
    }

    @Test
    void bindingIsFrozenButDeactivationBlocksNewSendsWithoutPreventingClose() {
        var c = open(Mode.V2);
        integrations.updateTarget(actor(owner), targetId, new TargetConfig("Changed", EnvironmentType.LIVE,
                "other-assistant", "other-environment", "other-workspace", null, null, "2021-11-27", "2024-08-25"));
        service.message(actor(owner), c.id(), new Message("Hello"), "frozen-binding-key");
        var binding = org.mockito.ArgumentCaptor.forClass(IbmConversationGateway.Binding.class);
        verify(ibm).message(binding.capture(), anyString(), eq(c.id()), eq("Hello"), anyMap());
        assertThat(binding.getValue().assistantId()).isEqualTo("assistant");
        assertThat(binding.getValue().environmentId()).isEqualTo("draft");
        integrations.deactivateTarget(actor(owner), targetId);
        assertThatThrownBy(() -> service.message(actor(owner), c.id(), new Message("Blocked"), "disabled-target-key"))
                .isInstanceOf(IntegrationException.class);
        assertThat(turns.count()).isEqualTo(1);
        assertThat(service.close(actor(owner), c.id()).state()).isEqualTo("CLOSED");
    }

    @Test
    void expiredLifecycleReservationsAreReconciledWithoutRepeatingCreation() {
        var input = new Open(UUID.fromString(targetId), Mode.V2);
        var opening = metadata.open(actor(owner), projectId, input, "crashed-opening-key");
        var row = conversations.findById(opening.view().id()).orElseThrow();
        ReflectionTestUtils.setField(row, "leaseUntil", 0L); conversations.saveAndFlush(row);
        assertThat(metadata.reconcileLifecycle(actor(owner), row.getId()).state()).isEqualTo("OPEN_UNKNOWN");
        assertThat(service.close(actor(owner), row.getId()).state()).isEqualTo("CLOSED");
        verify(ibm, never()).open(any());
        var active = open(Mode.V2);
        metadata.reserveClose(actor(owner), active.id());
        var closing = conversations.findById(active.id()).orElseThrow();
        ReflectionTestUtils.setField(closing, "leaseUntil", 0L); conversations.saveAndFlush(closing);
        assertThat(metadata.reconcileLifecycle(actor(owner), active.id()).state()).isEqualTo("CLOSE_UNKNOWN");
        assertThat(service.close(actor(owner), active.id()).state()).isEqualTo("CLOSED");
        verify(ibm, times(1)).close(any(), anyString());
    }

    @Test
    void lateFailureForOldTurnCannotUnblockAnotherUnknownTurn() {
        var c = open(Mode.V1);
        when(ibm.message(any(), isNull(), anyString(), anyString(), anyMap()))
                .thenThrow(new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_REQUEST_REJECTED", "Rejected"));
        var first = service.message(actor(owner), c.id(), new Message("Rejected"), "old-rejected-key");
        var second = metadata.reserveTurn(actor(owner), c.id(), "new-pending-turn-key", ConversationJson.hash("Next"));
        metadata.markSending(second.turn().id());
        metadata.failTurn(second.turn().id(), "IBM_TIMEOUT", true, false);
        metadata.failTurn(first.id(), "TURN_NOT_DISPATCHED", false, false);
        assertThat(metadata.read(actor(owner), c.id()).view().state()).isEqualTo("BLOCKED");
        assertThat(history.message(actor(owner), c.id(), second.turn().id()).state()).isEqualTo("UNKNOWN");
    }
    private View open(Mode mode) { return service.open(actor(owner), projectId, new Open(UUID.fromString(targetId), mode), UUID.randomUUID().toString()); }
    private AccountUser user(boolean admin) { return users.saveAndFlush(new AccountUser("Test", UUID.randomUUID() + "@example.invalid", "unused", admin)); }
    private static AccountPrincipal actor(AccountUser u) { return new AccountPrincipal(u.getId(), u.getAuthVersion()); }
    private static RequestPostProcessor as(AccountUser u) { return authentication(UsernamePasswordAuthenticationToken.authenticated(actor(u), null,
            List.of(new SimpleGrantedAuthority(u.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER")))); }
    private void expire(String id) { var t = turns.findById(id).orElseThrow(); ReflectionTestUtils.setField(t, "leaseUntil", 0L); turns.saveAndFlush(t); }
    private static void noTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
    private static ConversationException storageFailure() { return new ConversationException(HttpStatus.SERVICE_UNAVAILABLE, "CONVERSATION_STORAGE_UNAVAILABLE", "Unavailable"); }
    private static IbmConversationGateway.Reply reply() {
        return IbmConversationGateway.decode(Map.of("output", Map.of("generic", List.of(Map.of("response_type", "text", "text", "OK"))),
                "context", Map.of("counter", 1, "server_secret", "never expose")), true);
    }
}
