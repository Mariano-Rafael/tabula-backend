package com.tabula.tabula_backend.flow;

import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import com.tabula.tabula_backend.flow.repository.*;
import com.tabula.tabula_backend.flow.service.*;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.repository.*;
import com.tabula.tabula_backend.integration.service.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos;
import com.tabula.tabula_backend.portfolio.model.PortfolioRole;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class FlowModuleTests {
    private static final String RAW = "{\"type\":\"dialog\",\"workspace\":{\"dialog_nodes\":[]},\"unknown\":{\"$key.with.dot\":null,\"decimal\":1234567890.1234567890123456789}}";
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired IbmIntegrationRepository integrationRepository;
    @Autowired IbmTargetRepository targets;
    @Autowired FlowRepository flows;
    @Autowired FlowVersionRepository versions;
    @Autowired FlowWriteRepository outbox;
    @Autowired PortfolioService portfolios;
    @Autowired IntegrationService integrations;
    @Autowired FlowService service;
    @Autowired FlowMetadataService metadata;
    @Autowired FlowJsonCodec codec;
    @Autowired ObjectMapper json;
    @MockitoBean FlowDocumentStore documents;
    @MockitoBean IbmFlowExportService ibm;
    private final Map<String, Snapshot> stored = new ConcurrentHashMap<>();
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private AccountUser owner, other, admin;
    private String projectId, portfolioId;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        cleanup(); stored.clear(); drafts.clear();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = user(false); other = user(false); admin = user(true);
        portfolioId = portfolios.create(actor(owner), new PortfolioDtos.Create("Test", null)).id();
        projectId = portfolios.createProject(actor(owner), portfolioId, new PortfolioDtos.Create("Project", null)).id();
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            Snapshot s = call.getArgument(0); stored.putIfAbsent(s.documentId(), s); return null;
        }).when(documents).putSnapshot(any());
        when(documents.snapshot(anyString(), anyString())).thenAnswer(call -> {
            Snapshot s = stored.get(call.getArgument(1));
            return s != null && s.portfolioId().equals(call.getArgument(0)) ? Optional.of(s) : Optional.empty();
        });
        when(documents.initializeDraft(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            var d = new Draft(call.getArgument(0), call.getArgument(1), 1, call.getArgument(3), call.getArgument(2));
            if (drafts.putIfAbsent(d.flowId(), d) != null) { throw new FlowException(HttpStatus.CONFLICT, "DRAFT_EXISTS", "Already exists"); }
            return d;
        });
        when(documents.draft(anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(drafts.get(call.getArgument(1))));
        when(documents.updateDraft(anyString(), anyString(), anyLong(), anyString(), anyString())).thenAnswer(call -> {
            String id = call.getArgument(1); long expected = call.getArgument(2);
            var previous = drafts.get(id);
            if (previous == null || previous.revision() != expected) {
                throw new FlowException(HttpStatus.PRECONDITION_FAILED, "DRAFT_REVISION_MISMATCH", "Revision mismatch");
            }
            var d = new Draft(id, call.getArgument(0), expected + 1, call.getArgument(4), call.getArgument(3)); drafts.put(id, d); return d;
        });
    }
    @AfterEach
    void cleanup() {
        outbox.deleteAll(); versions.deleteAll(); flows.deleteAll(); targets.deleteAll(); integrationRepository.deleteAll();
        projects.deleteAll(); members.deleteAll(); portfolioRepository.deleteAll(); users.deleteAll();
    }

    @Test
    void uploadPreservesJsonUnknownKeysAndPrecisionAndIdempotency() throws Exception {
        var result = upload("upload-first-key");
        assertThat(result.state()).isEqualTo("READY");
        assertThat(service.export(actor(owner), result.id())).isEqualTo(RAW);
        assertThat(upload("upload-first-key").id()).isEqualTo(result.id());
        assertThat(versions.count()).isEqualTo(1);
        assertThatThrownBy(() -> service.upload(actor(owner), projectId, new Upload("Different", FlowType.DIALOG, RAW), "upload-first-key"))
                .isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        mvc.perform(get("/api/v1/versions/" + result.id() + "/export").with(as(owner)))
                .andExpect(status().isOk()).andExpect(content().json(RAW));
    }

    @Test
    void draftsRequireEtagAndNewSnapshotDoesNotChangeOriginalVersion() throws Exception {
        var first = upload("draft-first-key");
        service.startDraft(actor(owner), first.flowId(), first.id());
        mvc.perform(get("/api/v1/flows/" + first.flowId() + "/draft").with(as(owner)))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        String changed = RAW.replace("\"dialog_nodes\":[]", "\"dialog_nodes\":[{\"dialog_node\":\"node_1\"}]");
        mvc.perform(put("/api/v1/flows/" + first.flowId() + "/draft").with(as(owner)).with(csrf())
                .contentType("application/json").content(changed)).andExpect(status().isPreconditionRequired());
        mvc.perform(put("/api/v1/flows/" + first.flowId() + "/draft").with(as(owner)).with(csrf()).header("If-Match", "\"1\"")
                .contentType("application/json").content(changed)).andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
        mvc.perform(put("/api/v1/flows/" + first.flowId() + "/draft").with(as(owner)).with(csrf()).header("If-Match", "\"1\"")
                .contentType("application/json").content(RAW)).andExpect(status().isPreconditionFailed());
        var second = service.snapshotDraft(actor(owner), first.flowId(), "\"2\"", "draft-second-key");
        assertThat(second.number()).isEqualTo(2);
        assertThat(service.export(actor(owner), second.id())).isEqualTo(changed);
        assertThat(service.export(actor(owner), first.id())).isEqualTo(RAW);
    }

    @Test
    void nonMembersCannotReadDocumentsAndViewersCannotEdit() throws Exception {
        var result = upload("access-test-key");
        mvc.perform(get("/api/v1/versions/" + result.id() + "/export").with(as(other))).andExpect(status().isNotFound());
        portfolios.addMember(actor(owner), portfolioId, new PortfolioDtos.AddMember(UUID.fromString(other.getId()), PortfolioRole.VIEWER));
        mvc.perform(get("/api/v1/flows/" + result.flowId()).with(as(other))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/projects/" + projectId + "/flows").with(as(other)).with(csrf())
                .header("Idempotency-Key", "viewer-upload-key").contentType("application/json")
                .content(json.writeValueAsString(new Upload("Test", FlowType.DIALOG, RAW)))).andExpect(status().isForbidden());
        portfolios.changeRole(actor(owner), portfolioId, other.getId(), new PortfolioDtos.ChangeRole(PortfolioRole.EDITOR));
        mvc.perform(post("/api/v1/projects/" + projectId + "/flows").with(as(other)).with(csrf())
                .header("Idempotency-Key", "editor-upload-key").contentType("application/json")
                .content(json.writeValueAsString(new Upload("Test", FlowType.DIALOG, RAW)))).andExpect(status().isCreated());
    }

    @Test
    void mongoFailureNeverExposesReadyVersionAndReconciliationCanFinishAmbiguousWrites() {
        doThrow(new FlowException(HttpStatus.SERVICE_UNAVAILABLE, "FLOW_STORAGE_UNAVAILABLE", "Unavailable")).when(documents).putSnapshot(any());
        assertThatThrownBy(() -> upload("failed-write-key")).isInstanceOf(FlowException.class);
        var failed = versions.findByProjectIdAndRequestKey(projectId, "failed-write-key").orElseThrow();
        assertThat(failed.getState()).isEqualTo("FAILED");
        assertThatThrownBy(() -> service.export(actor(owner), failed.getId())).isInstanceOf(FlowException.class);
        var expected = metadata.expected(actor(owner), failed.getId(), true);
        stored.put(expected.documentId(), new Snapshot(expected.documentId(), expected.versionId(), expected.flowId(), projectId,
                portfolioId, expected.checksum(), RAW));
        assertThat(service.reconcile(actor(owner), failed.getId()).state()).isEqualTo("READY");
        assertThat(service.export(actor(owner), failed.getId())).isEqualTo(RAW);
    }

    @Test
    void pendingWriteIsProtectedByLeaseAndMissingContentCanBeMarkedFailed() {
        String checksum = codec.validate(RAW, FlowType.DIALOG);
        var reservation = metadata.reserve(actor(owner), projectId, null, "Pending", FlowType.DIALOG, "pending-write-key", "a".repeat(64), checksum, RAW, "UPLOAD", null);
        assertThatThrownBy(() -> service.export(actor(owner), reservation.version().id())).isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> service.reconcile(actor(owner), reservation.version().id())).isInstanceOfSatisfying(FlowException.class,
                ex -> assertThat(ex.code()).isEqualTo("FLOW_WRITE_IN_PROGRESS"));
        var write = outbox.findById(reservation.version().id()).orElseThrow();
        ReflectionTestUtils.setField(write, "leaseUntil", 0L); outbox.saveAndFlush(write);
        assertThat(service.reconcile(actor(owner), reservation.version().id()).state()).isEqualTo("FAILED");
    }

    @Test
    void checksumMismatchBlocksExportAndArchivedFlowRejectsNewSnapshots() throws Exception {
        var result = upload("integrity-write-key");
        var expected = metadata.expected(actor(owner), result.id(), false);
        stored.put(expected.documentId(), new Snapshot(expected.documentId(), expected.versionId(), expected.flowId(), projectId, portfolioId, expected.checksum(), RAW + " "));
        // Insignificant whitespace is structurally equivalent under canonical hashing.
        assertThat(service.export(actor(owner), result.id())).isEqualTo(RAW + " ");
        stored.put(expected.documentId(), new Snapshot(expected.documentId(), expected.versionId(), expected.flowId(), projectId, portfolioId, expected.checksum(), RAW.replace("dialog_nodes", "changed_nodes")));
        assertThatThrownBy(() -> service.export(actor(owner), result.id())).isInstanceOfSatisfying(FlowException.class,
                ex -> assertThat(ex.code()).isEqualTo("FLOW_INTEGRITY_ERROR"));
        metadata.archive(actor(owner), result.flowId());
        mvc.perform(post("/api/v1/flows/" + result.flowId() + "/draft").with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"versionId\":\"" + result.id() + "\"}")).andExpect(status().isConflict());
    }

    @Test
    void ibmPendingExportCreatesNoIncompleteSnapshotAndCanBeRetried() throws Exception {
        UUID targetId = UUID.randomUUID();
        when(ibm.export(any(), eq(projectId), eq(targetId.toString()), eq("DIALOG")))
                .thenThrow(new IntegrationException(HttpStatus.ACCEPTED, "IBM_EXPORT_PENDING", "Processing"))
                .thenAnswer(call -> {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                    return Map.of("type", "dialog", "workspace", Map.of("dialog_nodes", List.of()));
                });
        String body = json.writeValueAsString(new IbmImport(targetId, FlowType.DIALOG, "IBM dialog", null));
        mvc.perform(post("/api/v1/projects/" + projectId + "/imports").with(as(owner)).with(csrf())
                .header("Idempotency-Key", "ibm-import-key").contentType("application/json").content(body))
                .andExpect(status().isAccepted()).andExpect(header().string("Retry-After", "2"));
        assertThat(versions.count()).isZero();
        mvc.perform(post("/api/v1/projects/" + projectId + "/imports").with(as(owner)).with(csrf())
                .header("Idempotency-Key", "ibm-import-key").contentType("application/json").content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("READY"));
    }

    @Test
    void validationRejectsWrongTypeInvalidJsonAndMissingIdempotency() throws Exception {
        assertThatThrownBy(() -> codec.validate("{", FlowType.DIALOG)).isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> codec.validate(RAW, FlowType.ACTION)).isInstanceOf(FlowException.class);
        mvc.perform(post("/api/v1/projects/" + projectId + "/flows").with(as(owner)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(new Upload("Test", FlowType.DIALOG, RAW)))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/projects/" + projectId + "/flows").with(as(owner)).header("Idempotency-Key", "no-csrf-key")
                .contentType("application/json").content(json.writeValueAsString(new Upload("Test", FlowType.DIALOG, RAW)))).andExpect(status().isForbidden());
        assertThat(versions.count()).isZero();
    }

    @Test
    void concurrentIdempotentUploadsCreateOnlyOneSnapshot() throws Exception {
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<VersionView>>();
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) { throw new AssertionError("Start timeout"); }
                    return upload("concurrent-upload-key");
                }));
            }
            start.countDown();
            var first = results.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS);
            var second = results.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(first.id()).isEqualTo(second.id());
            assertThat(versions.count()).isEqualTo(1);
            verify(documents, times(1)).putSnapshot(any());
        }
    }
    private VersionView upload(String key) { return service.upload(actor(owner), projectId, new Upload("Test", FlowType.DIALOG, RAW), key); }
    private AccountUser user(boolean admin) { return users.saveAndFlush(new AccountUser("Test", UUID.randomUUID() + "@example.invalid", "unused", admin)); }
    private static AccountPrincipal actor(AccountUser u) { return new AccountPrincipal(u.getId(), u.getAuthVersion()); }
    private static RequestPostProcessor as(AccountUser u) { return authentication(UsernamePasswordAuthenticationToken.authenticated(actor(u), null,
            List.of(new SimpleGrantedAuthority(u.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER")))); }
}
