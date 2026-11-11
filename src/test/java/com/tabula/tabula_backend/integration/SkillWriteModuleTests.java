package com.tabula.tabula_backend.integration;

import com.tabula.tabula_backend.flow.dto.FlowDtos;
import com.tabula.tabula_backend.flow.service.*;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.integration.client.IbmSkillWriteGateway;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.dto.SkillWriteDtos.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "ibm.watson.service-url=https://api.us-south.assistant.watson.cloud.ibm.com/instances/test",
        "ibm.watson.credentials-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class SkillWriteModuleTests {
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired IbmIntegrationRepository integrationRepository;
    @Autowired IbmTargetRepository targets;
    @Autowired PortfolioService portfolios;
    @Autowired IntegrationService integrations;
    @Autowired SkillWriteService service;
    @Autowired SkillWriteLedger ledger;
    @Autowired JdbcTemplate sql;
    @Autowired WebApplicationContext context;
    @MockitoBean FlowService flows;
    @MockitoBean FlowMetadataService versions;
    @MockitoBean IbmFlowExportService exports;
    @MockitoBean IbmSkillWriteGateway gateway;
    @MockitoBean SkillWriteDocuments documents;
    private AccountPrincipal actor, viewer, operator, stranger;
    private String portfolioId, projectId, targetId, versionId, flowId;
    private MockMvc mvc;
    private final Map<String, Documents> stored = new ConcurrentHashMap<>();
    private static final Map<String, Object> BEFORE = Map.of("dialog_nodes", List.of());
    private static final Map<String, Object> AFTER = Map.of("dialog_nodes", List.of(Map.of("dialog_node", "new", "conditions", "true")));

    @BeforeEach void setup() {
        clean(); stored.clear();
        actor = user(true); viewer = user(false); operator = user(false); stranger = user(false);
        portfolioId = portfolios.create(actor, new PortfolioDtos.Create("Writes", null)).id();
        projectId = portfolios.createProject(actor, portfolioId, new PortfolioDtos.Create("Draft", null)).id();
        portfolios.addMember(actor, portfolioId, new PortfolioDtos.AddMember(UUID.fromString(viewer.id()), PortfolioRole.VIEWER));
        portfolios.addMember(actor, portfolioId, new PortfolioDtos.AddMember(UUID.fromString(operator.id()), PortfolioRole.OPERATOR));
        String integrationId = integrations.create(actor, portfolioId, new CreateIntegration("IBM")).id();
        targetId = integrations.createTarget(actor, projectId, new CreateTarget(UUID.fromString(integrationId), config(EnvironmentType.DRAFT))).id();
        versionId = UUID.randomUUID().toString(); flowId = UUID.randomUUID().toString();
        when(versions.version(any(), eq(versionId))).thenReturn(new FlowDtos.VersionView(versionId, flowId, projectId, 1, "READY", "unused", "UPLOAD", null, actor.id(), "now", null));
        when(versions.editable(any(), eq(flowId))).thenReturn(new FlowDtos.View(flowId, projectId, "Flow", FlowDtos.FlowType.DIALOG, false));
        when(flows.export(any(), eq(versionId))).thenReturn("{\"type\":\"dialog\",\"workspace\":{\"dialog_nodes\":[{\"dialog_node\":\"new\",\"conditions\":\"true\"}]}}");
        when(exports.exportSkill(anyString(), anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of("workspace", BEFORE);
        });
        doAnswer(call -> { var d = (Documents) call.getArgument(0); stored.put(d.id(), d); return null; }).when(documents).put(any());
        when(documents.get(anyString(), anyString())).thenAnswer(call -> stored.get(call.getArgument(1)));
        when(gateway.update(any(), anyMap())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(sql.queryForObject("SELECT COUNT(*) FROM ibm_skill_writes WHERE state='SENDING'", Integer.class)).isEqualTo(1);
            return Map.of("status", "Processing");
        });
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    @AfterEach void clean() {
        sql.update("DELETE FROM ibm_skill_write_locks"); sql.update("DELETE FROM ibm_skill_writes");
        targets.deleteAll(); integrationRepository.deleteAll(); projects.deleteAll(); members.deleteAll(); portfolioRepository.deleteAll(); users.deleteAll();
    }
    @Test void prepareBacksUpWithoutMutationAndReplaysSameRequest() {
        var op = prepare("prepare-key-001");
        assertThat(op.state()).isEqualTo("READY");
        assertThat(prepare("prepare-key-001").id()).isEqualTo(op.id());
        assertThat(service.backup(actor, op.id()).baselineJson()).isEqualTo("{\"dialog_nodes\":[]}");
        verifyNoInteractions(gateway);
        verify(documents, times(1)).put(any());
    }
    @Test void appliesOnceAndOnlyConfirmsAfterRemoteContentReadback() {
        var op = prepare("prepare-key-001");
        assertThat(service.apply(actor, op.id()).state()).isEqualTo("PROCESSING");
        service.apply(actor, op.id());
        verify(gateway, times(1)).update(any(), anyMap());
        when(gateway.status(any())).thenReturn(Map.of("status", "Available"));
        when(exports.exportSkill(anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("workspace", AFTER));
        assertThat(service.reconcile(actor, op.id()).state()).isEqualTo("SUCCEEDED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ibm_skill_write_locks", Integer.class)).isZero();
    }
    @Test void detectsRemoteChangesBeforeDispatch() {
        var op = prepare("prepare-key-001");
        when(exports.exportSkill(anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("workspace", AFTER));
        assertThat(service.apply(actor, op.id()).state()).isEqualTo("CONFLICT");
        verifyNoInteractions(gateway);
    }
    @Test void pendingPreflightReturnsReadyWithoutDispatchAndCanBeRetried() {
        var op = prepare("prepare-key-001");
        doThrow(new IntegrationException(HttpStatus.ACCEPTED, "IBM_EXPORT_PENDING", "pending")).when(exports).exportSkill(anyString(), anyString(), anyString(), anyString());
        assertThat(service.apply(actor, op.id()).state()).isEqualTo("READY");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ibm_skill_write_locks", Integer.class)).isZero();
        verifyNoInteractions(gateway);
    }
    @Test void staleSendingBecomesUnknownAndOnlyReadsProvider() {
        var op = prepare("prepare-key-001"); ledger.claim(op.id()); ledger.sending(op.id());
        sql.update("UPDATE ibm_skill_writes SET lease_until=0 WHERE id=?", op.id());
        when(gateway.status(any())).thenReturn(Map.of("status", "Processing"));
        assertThat(service.reconcile(actor, op.id()).state()).isEqualTo("UNKNOWN");
        verify(gateway, never()).update(any(), anyMap());
    }
    @Test void rateLimitedPreflightPreservesReadyAndCanBeExplicitlyRetried() {
        var op = prepare("prepare-key-001");
        doThrow(new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "IBM_RATE_LIMITED", "limited")).when(exports).exportSkill(anyString(), anyString(), anyString(), anyString());
        var deferred = service.apply(actor, op.id());
        assertThat(deferred.state()).isEqualTo("READY");
        assertThat(deferred.errorCode()).isEqualTo("IBM_RATE_LIMITED");
        verifyNoInteractions(gateway);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ibm_skill_write_locks", Integer.class)).isZero();
        doReturn(Map.of("workspace", BEFORE)).when(exports).exportSkill(anyString(), anyString(), anyString(), anyString());
        var dispatched = service.apply(actor, op.id());
        assertThat(dispatched.state()).isEqualTo("PROCESSING");
        assertThat(dispatched.errorCode()).isNull();
        verify(gateway, times(1)).update(any(), anyMap());
    }
    @Test void explicitProviderRejectionFailsAndReleasesLock() {
        var op = prepare("prepare-key-001");
        doThrow(new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_PERMISSION_DENIED", "denied")).when(gateway).update(any(), anyMap());
        assertThat(service.apply(actor, op.id()).state()).isEqualTo("FAILED");
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ibm_skill_write_locks", Integer.class)).isZero();
    }
    @Test void timeoutKeepsLockAndNeverRetriesMutation() {
        var op = prepare("prepare-key-001");
        doThrow(new IntegrationException(HttpStatus.GATEWAY_TIMEOUT, "IBM_TIMEOUT", "sanitized")).when(gateway).update(any(), anyMap());
        assertThat(service.apply(actor, op.id()).state()).isEqualTo("UNKNOWN");
        service.apply(actor, op.id());
        verify(gateway, times(1)).update(any(), anyMap());
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ibm_skill_write_locks", Integer.class)).isEqualTo(1);
        when(gateway.status(any())).thenReturn(Map.of("status", "Available"));
        assertThat(service.reconcile(actor, op.id()).state()).isEqualTo("UNKNOWN");
        when(exports.exportSkill(anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("workspace", AFTER));
        assertThat(service.reconcile(actor, op.id()).state()).isEqualTo("SUCCEEDED");
    }
    @Test void duplicateBindingsCannotWriteSameRemoteSkillConcurrently() {
        var first = prepare("prepare-key-001"); var second = prepare("prepare-key-002");
        service.apply(actor, first.id());
        assertThatThrownBy(() -> service.apply(actor, second.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        verify(gateway, times(1)).update(any(), anyMap());
        assertThat(service.find(actor, second.id()).state()).isEqualTo("READY");
    }
    @Test void corruptedBackupBlocksDispatch() {
        var op = prepare("prepare-key-001"); var original = stored.get(op.id());
        stored.put(op.id(), new Documents(op.id(), projectId, original.baselineJson(), "{}"));
        assertThatThrownBy(() -> service.apply(actor, op.id())).isInstanceOf(IntegrationException.class);
        verifyNoInteractions(gateway);
    }
    @Test void missingMongoDurabilityPreventsReadyState() {
        doThrow(new IntegrationException(HttpStatus.SERVICE_UNAVAILABLE, "IBM_WRITE_DOCUMENT_UNAVAILABLE", "sanitized")).when(documents).put(any());
        assertThatThrownBy(() -> prepare("prepare-key-001")).isInstanceOf(IntegrationException.class);
        assertThat(sql.queryForObject("SELECT state FROM ibm_skill_writes", String.class)).isEqualTo("FAILED");
        verifyNoInteractions(gateway);
    }
    @Test void changedTargetAndLiveTargetsAreRejected() {
        var op = prepare("prepare-key-001");
        integrations.updateTarget(actor, targetId, config(EnvironmentType.LIVE));
        assertThatThrownBy(() -> service.apply(actor, op.id())).isInstanceOf(IntegrationException.class);
        assertThatThrownBy(() -> prepare("prepare-key-002")).isInstanceOf(IntegrationException.class);
        verifyNoInteractions(gateway);
    }
    @Test void reconcileSurvivesArchivalAndUsesFrozenBinding() {
        var op = prepare("prepare-key-001"); service.apply(actor, op.id());
        integrations.deactivateTarget(actor, targetId); portfolios.archiveProject(actor, projectId); portfolios.archive(actor, portfolioId);
        when(gateway.status(any())).thenReturn(Map.of("status", "Available"));
        when(exports.exportSkill(anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("workspace", AFTER));
        assertThat(service.reconcile(actor, op.id()).state()).isEqualTo("SUCCEEDED");
    }
    @Test void staleUnsentOperationFailsWithoutDispatch() {
        var op = prepare("prepare-key-001"); ledger.claim(op.id());
        sql.update("UPDATE ibm_skill_writes SET lease_until=0 WHERE id=?", op.id());
        assertThat(service.reconcile(actor, op.id()).state()).isEqualTo("FAILED");
        verifyNoInteractions(gateway);
    }
    @Test void httpEnforcesCsrfScopeAndEditingRole() throws Exception {
        var op = prepare("prepare-key-001");
        mvc.perform(post("/api/v1/ibm-writes/" + op.id() + "/apply").with(authentication(auth(actor)))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/ibm-writes/" + op.id() + "/apply").with(authentication(auth(operator))).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/ibm-writes/" + op.id()).with(authentication(auth(stranger)))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/ibm-writes/" + op.id()).with(authentication(auth(viewer)))).andExpect(status().isOk()).andExpect(jsonPath("$.bindingJson").doesNotExist());
        mvc.perform(get("/api/v1/projects/" + projectId + "/ibm-writes").with(authentication(auth(viewer))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(op.id()));
        mvc.perform(get("/api/v1/ibm-writes/" + op.id() + "/backup").with(authentication(auth(viewer)))).andExpect(status().isForbidden());
    }
    @Test void actionSkillUsesSameV2PathWithoutWorkspaceId() {
        when(versions.editable(any(), eq(flowId))).thenReturn(new FlowDtos.View(flowId, projectId, "Action", FlowDtos.FlowType.ACTION, false));
        when(flows.export(any(), eq(versionId))).thenReturn("{\"type\":\"action\",\"workspace\":{\"actions\":[]}}");
        var op = prepare("prepare-key-001"); service.apply(actor, op.id());
        verify(gateway).update(eq(new Binding("assistant", "action", "2024-08-25")), eq(Map.of("actions", List.of())));
    }
    @Test void httpUpdatesClassicDialogUsingRegisteredIdsAndIsolatedConnectionWithoutWorkspaceId() throws Exception {
        String isolatedPortfolio = portfolios.create(actor, new PortfolioDtos.Create("Isolated", null)).id();
        String isolatedProject = portfolios.createProject(actor, isolatedPortfolio, new PortfolioDtos.Create("IBM", null)).id();
        String integration = integrations.create(actor, isolatedPortfolio, new CreateIntegration("IBM isolated",
                "https://api.us-south.assistant.watson.cloud.ibm.com/instances/registered-instance", "fake-key")).id();
        var configuration = new TargetConfig("Dialog", EnvironmentType.DRAFT, "registered-assistant", "registered-environment",
                null, "registered-dialog", null, "2021-11-27", "2024-08-25");
        String target = integrations.createTarget(actor, isolatedProject, new CreateTarget(UUID.fromString(integration), configuration)).id();
        when(versions.version(any(), eq(versionId))).thenReturn(new FlowDtos.VersionView(versionId, flowId, isolatedProject,
                1, "READY", "unused", "UPLOAD", null, actor.id(), "now", null));
        when(versions.editable(any(), eq(flowId))).thenReturn(new FlowDtos.View(flowId, isolatedProject, "Dialog", FlowDtos.FlowType.DIALOG, false));
        var connection = integrations.connectionReference(integration);
        var binding = new Binding("registered-assistant", "registered-dialog", "2024-08-25", connection);
        when(exports.exportSkill("registered-assistant", "registered-dialog", "2024-08-25", "DIALOG", connection))
                .thenReturn(Map.of("workspace", BEFORE));
        mvc.perform(post("/api/v1/projects/" + isolatedProject + "/ibm-writes")
                        .with(authentication(auth(actor))).with(csrf()).header("Idempotency-Key", "registered-dialog-001")
                        .contentType("application/json").content("{\"targetId\":\"" + target + "\",\"versionId\":\"" + versionId + "\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.state").value("READY"));
        var operation = service.list(actor, isolatedProject, 0, 20).content().get(0);
        mvc.perform(post("/api/v1/ibm-writes/" + operation.id() + "/apply").with(authentication(auth(actor))).with(csrf()))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.state").value("PROCESSING"));
        mvc.perform(post("/api/v1/ibm-writes/" + operation.id() + "/apply").with(authentication(auth(actor))).with(csrf()))
                .andExpect(status().isAccepted());
        verify(gateway, times(1)).update(eq(binding), eq(AFTER));
        when(gateway.status(binding)).thenReturn(Map.of("status", "Available"));
        when(exports.exportSkill("registered-assistant", "registered-dialog", "2024-08-25", "DIALOG", connection))
                .thenReturn(Map.of("workspace", AFTER));
        mvc.perform(post("/api/v1/ibm-writes/" + operation.id() + "/reconcile").with(authentication(auth(actor))).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("SUCCEEDED"));
    }
    @Test void localCanvasCannotBePreparedForIbmUpdate() {
        when(versions.editable(any(), eq(flowId))).thenReturn(new FlowDtos.View(flowId, projectId, "Local", FlowDtos.FlowType.LOCAL, false));
        assertThatThrownBy(() -> prepare("local-write-blocked"))
                .isInstanceOfSatisfying(IntegrationException.class, ex -> assertThat(ex.code()).isEqualTo("LOCAL_FLOW_IBM_UNSUPPORTED"));
        verifyNoInteractions(exports, gateway, documents);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM ibm_skill_writes", Integer.class)).isZero();
    }
    private View prepare(String key) { return service.prepare(actor, projectId, new Prepare(UUID.fromString(targetId), UUID.fromString(versionId)), key); }
    private TargetConfig config(EnvironmentType type) { return new TargetConfig("Draft", type, "assistant", "environment", null, "dialog", "action", "2021-11-27", "2024-08-25"); }
    private AccountPrincipal user(boolean admin) { var u = users.saveAndFlush(new AccountUser("Test", UUID.randomUUID()+"@example.invalid", "unused", admin)); return new AccountPrincipal(u.getId(), u.getAuthVersion()); }
    private org.springframework.security.core.Authentication auth(AccountPrincipal p) { return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(p, null, List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))); }
}
