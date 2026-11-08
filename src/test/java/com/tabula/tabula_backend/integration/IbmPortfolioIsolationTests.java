package com.tabula.tabula_backend.integration;

import com.tabula.tabula_backend.conversation.dto.ConversationDtos;
import com.tabula.tabula_backend.conversation.repository.*;
import com.tabula.tabula_backend.conversation.service.ConversationService;
import com.tabula.tabula_backend.flow.dto.FlowDtos;
import com.tabula.tabula_backend.flow.repository.*;
import com.tabula.tabula_backend.flow.service.FlowService;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.integration.client.*;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.dto.SkillWriteDtos;
import com.tabula.tabula_backend.integration.repository.*;
import com.tabula.tabula_backend.integration.service.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {"ibm.watson.service-url=https://api.us-south.assistant.watson.cloud.ibm.com/instances/legacy",
        "ibm.watson.credentials-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class IbmPortfolioIsolationTests {
    private static final String A = "https://api.us-south.assistant.watson.cloud.ibm.com/instances/alpha";
    private static final String B = "https://api.eu-de.assistant.watson.cloud.ibm.com/instances/beta";
    @Autowired IbmClientFactory factory;
    @Autowired IbmCredentialCipher cipher;
    @Autowired IntegrationService integrations;
    @Autowired ConnectionTestService probes;
    @Autowired IbmFlowExportService exports;
    @Autowired FlowService flows;
    @Autowired SkillWriteService writes;
    @Autowired SkillWriteLedger ledger;
    @Autowired ConversationService conversations;
    @Autowired ConversationRepository conversationRows;
    @Autowired ConversationTurnRepository turns;
    @Autowired IbmConversationGateway conversationGateway;
    @Autowired UserRepository users;
    @Autowired PortfolioService portfolios;
    @Autowired PortfolioRepository portfolioRows;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired IbmIntegrationRepository integrationRows;
    @Autowired IbmTargetRepository targets;
    @Autowired FlowRepository flowRows;
    @Autowired FlowVersionRepository versions;
    @Autowired FlowWriteRepository outbox;
    @Autowired JdbcTemplate sql;
    @Autowired ObjectMapper json;
    @Autowired WebApplicationContext context;
    @Autowired @Qualifier("ibmRestClient") RestClient productionRest;
    @MockitoBean FlowDocumentStore documents;
    @MockitoBean SkillWriteDocuments writeDocuments;
    private MockRestServiceServer server;
    private MockMvc mvc;
    private AccountPrincipal owner, other;
    private String portfolioA, projectA, integrationA, targetA, projectB, integrationB, targetB;
    private final Map<String, FlowDtos.Snapshot> snapshots = new ConcurrentHashMap<>();
    private final Map<String, SkillWriteDtos.Documents> backups = new ConcurrentHashMap<>();
    @BeforeEach void setup() {
        cleanup(); snapshots.clear(); backups.clear();
        var builder = RestClient.builder(); server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        ReflectionTestUtils.setField(factory, "rest", builder.build());
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = user(); other = user();
        portfolioA = portfolios.create(owner, new PortfolioDtos.Create("Alpha", null)).id();
        String portfolioB = portfolios.create(other, new PortfolioDtos.Create("Beta", null)).id();
        projectA = portfolios.createProject(owner, portfolioA, new PortfolioDtos.Create("Alpha project", null)).id();
        projectB = portfolios.createProject(other, portfolioB, new PortfolioDtos.Create("Beta project", null)).id();
        integrationA = integrations.create(owner, portfolioA, new CreateIntegration("Alpha", A, "fake-alpha-key")).id();
        integrationB = integrations.create(other, portfolioB, new CreateIntegration("Beta", B, "fake-beta-key")).id();
        targetA = integrations.createTarget(owner, projectA, new CreateTarget(UUID.fromString(integrationA), config())).id();
        targetB = integrations.createTarget(other, projectB, new CreateTarget(UUID.fromString(integrationB), config())).id();
        doAnswer(call -> { var d = (FlowDtos.Snapshot) call.getArgument(0); snapshots.put(d.documentId(), d); return null; }).when(documents).putSnapshot(any());
        when(documents.snapshot(anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(snapshots.get(call.getArgument(1))));
        doAnswer(call -> { var d = (SkillWriteDtos.Documents) call.getArgument(0); backups.put(d.id(), d); return null; }).when(writeDocuments).put(any());
        when(writeDocuments.get(anyString(), anyString())).thenAnswer(call -> backups.get(call.getArgument(1)));
    }
    @AfterEach void cleanup() {
        if (factory != null && productionRest != null) ReflectionTestUtils.setField(factory, "rest", productionRest);
        sql.update("DELETE FROM ibm_skill_write_locks"); sql.update("DELETE FROM ibm_skill_writes");
        turns.deleteAll(); conversationRows.deleteAll(); outbox.deleteAll(); versions.deleteAll(); flowRows.deleteAll();
        targets.deleteAll(); integrationRows.deleteAll(); projects.deleteAll(); members.deleteAll(); portfolioRows.deleteAll(); users.deleteAll();
    }
    @Test void ownerCanLinkOwnInstanceAndSecretsAreEncryptedAndNeverReturned() throws Exception {
        var entity = integrationRows.findById(integrationA).orElseThrow();
        assertThat(entity.getApiKeyCiphertext()).startsWith("v1.").doesNotContain("fake-alpha-key");
        assertThat(cipher.decrypt(entity.getApiKeyCiphertext(), entity.credentialContext())).isEqualTo("fake-alpha-key");
        assertThatThrownBy(() -> cipher.decrypt(entity.getApiKeyCiphertext(), integrationRows.findById(integrationB).orElseThrow().credentialContext())).isInstanceOf(IntegrationException.class);
        mvc.perform(get("/api/v1/integrations/"+integrationA).with(authentication(auth(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credentialSource").value("DATABASE"))
                .andExpect(jsonPath("$.serviceUrl").value(A)).andExpect(jsonPath("$.apiKey").doesNotExist()).andExpect(jsonPath("$.apiKeyCiphertext").doesNotExist());
        mvc.perform(get("/api/v1/integrations/"+integrationA).with(authentication(auth(other)))).andExpect(status().isNotFound());
        assertThat(json.writeValueAsString(new CreateIntegration("Alpha", A, "fake-alpha-key"))).doesNotContain("fake-alpha-key");
        assertThat(new CreateIntegration("Alpha", A, "fake-alpha-key").toString()).doesNotContain("fake-alpha-key");
        var oldConversation = json.readValue("{\"mode\":\"V2\",\"workspaceId\":null,\"assistantId\":\"assistant\",\"environmentId\":\"draft\",\"version\":\"2024-08-25\"}", IbmConversationGateway.Binding.class);
        var oldWrite = json.readValue("{\"assistantId\":\"assistant\",\"skillId\":\"dialog\",\"version\":\"2024-08-25\"}", SkillWriteDtos.Binding.class);
        assertThat(oldConversation.connection()).isNull(); assertThat(oldWrite.connection()).isNull();
        assertThatThrownBy(() -> new IbmCredentialCipher("").encrypt("fake", "context")).isInstanceOf(IntegrationException.class);
        server.verify();
    }
    @Test void probesKeepTokensEndpointsAndRotationIsolated() {
        iam("fake-alpha-key", "alpha-token"); probeReads(A, "alpha-token");
        iam("fake-beta-key", "beta-token"); probeReads(B, "beta-token");
        probeReads(A, "alpha-token");
        iam("fake-alpha-next-key", "alpha-next-token"); probeReads(A, "alpha-next-token");
        probeReads(B, "beta-token");
        assertThat(probes.integration(owner, integrationA).successful()).isTrue();
        assertThat(probes.integration(other, integrationB).successful()).isTrue();
        assertThat(probes.integration(owner, integrationA).successful()).isTrue();
        integrations.rotateCredential(owner, integrationA, new RotateCredential("fake-alpha-next-key"));
        assertThat(probes.integration(owner, integrationA).successful()).isTrue();
        assertThat(probes.integration(other, integrationB).successful()).isTrue();
        assertThat(integrations.find(owner, integrationA).credentialRevision()).isEqualTo(2);
        server.verify();
    }
    @Test void importsUseTheInstanceLinkedToTheirOwnPortfolio() {
        iam("fake-alpha-key", "alpha-token"); export(A, "alpha-token", "alpha-node");
        iam("fake-beta-key", "beta-token"); export(B, "beta-token", "beta-node");
        var a = flows.importIbm(owner, projectA, new FlowDtos.IbmImport(UUID.fromString(targetA), FlowDtos.FlowType.DIALOG, "Alpha", null), "alpha-import-key");
        var b = flows.importIbm(other, projectB, new FlowDtos.IbmImport(UUID.fromString(targetB), FlowDtos.FlowType.DIALOG, "Beta", null), "beta-import-key");
        assertThat(flows.export(owner, a.id())).contains("alpha-node").doesNotContain("beta-node");
        assertThat(flows.export(other, b.id())).contains("beta-node").doesNotContain("alpha-node");
        assertThatThrownBy(() -> flows.importIbm(owner, projectA, new FlowDtos.IbmImport(UUID.fromString(targetB), FlowDtos.FlowType.DIALOG, "Wrong", null), "cross-import-key")).isInstanceOf(RuntimeException.class);
        server.verify();
    }
    @Test void simultaneousRequestsShareOnlyTheirOwnConnectionToken() throws Exception {
        iam("fake-alpha-key", "alpha-token"); iam("fake-beta-key", "beta-token");
        var a = factory.clients(factory.reference(integrationA)); var b = factory.clients(factory.reference(integrationB));
        assertThat(a.tokens()).isNotSameAs(b.tokens());
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<String>>();
            for (var client : List.of(a, b, a, b)) futures.add(executor.submit(() -> { start.await(); return client.tokens().token(); }));
            start.countDown();
            assertThat(futures.get(0).get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("alpha-token");
            assertThat(futures.get(1).get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("beta-token");
            assertThat(futures.get(2).get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("alpha-token");
            assertThat(futures.get(3).get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("beta-token");
        }
        server.verify();
    }
    @Test void customDefaultsNeverLeakGlobalIdsAndTamperedConnectionsNeverFallback() throws Exception {
        mvc.perform(get("/api/v1/integrations/"+integrationA+"/defaults?environmentType=DRAFT").with(authentication(auth(owner))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assistantId").isEmpty()).andExpect(jsonPath("$.dialogSkillId").isEmpty());
        assertThatThrownBy(() -> factory.clients(new IbmConnectionRef(integrationA, B))).isInstanceOf(IntegrationException.class);
        sql.update("UPDATE ibm_integrations SET api_key_ciphertext='broken' WHERE id=?", integrationA);
        assertThatThrownBy(() -> factory.clients(factory.reference(integrationA))).isInstanceOfSatisfying(IntegrationException.class,
                ex -> assertThat(ex.code()).isEqualTo("IBM_CREDENTIAL_ENCRYPTION_UNAVAILABLE"));
        server.verify();
    }
    @Test void sessionsFreezeConnectionAndCloseOnOriginalInstanceEvenAfterTargetChanges() {
        String path = A+"/v2/assistants/assistant/environments/draft/sessions";
        iam("fake-alpha-key", "alpha-token");
        expect(path, "alpha-token", HttpMethod.POST, "{\"session_id\":\"session\"}");
        expect(path+"/session/message", "alpha-token", HttpMethod.POST, "{\"output\":{\"generic\":[]}}");
        server.expect(requestTo(path+"/session?version=2024-08-25")).andExpect(method(HttpMethod.DELETE)).andExpect(header(HttpHeaders.AUTHORIZATION,"Bearer alpha-token")).andRespond(withNoContent());
        var opened = conversations.open(owner, projectA, new ConversationDtos.Open(UUID.fromString(targetA), ConversationDtos.Mode.V2), "session-alpha-key");
        assertThat(opened.state()).isEqualTo("ACTIVE");
        var stored = conversationRows.findById(opened.id()).orElseThrow();
        var binding = json.readValue(stored.getBindingJson(), IbmConversationGateway.Binding.class);
        assertThat(binding.connection().integrationId()).isEqualTo(integrationA);
        assertThat(stored.getBindingJson()).doesNotContain("fake-alpha-key");
        integrations.deactivateTarget(owner, targetA);
        conversationGateway.message(binding, "session", opened.id(), "fake-input", Map.of());
        assertThat(conversations.close(owner, opened.id()).state()).isEqualTo("CLOSED");
        server.verify();
    }
    @Test void draftWritesAndReadbackUseFrozenConnectionAndResourceIdentity() {
        var source = flows.upload(owner, projectA, new FlowDtos.Upload("Edited", FlowDtos.FlowType.DIALOG,
                "{\"type\":\"dialog\",\"workspace\":{\"dialog_nodes\":[{\"dialog_node\":\"edited-node\"}]}}"), "write-upload-key");
        iam("fake-alpha-key", "alpha-token"); export(A, "alpha-token", "original-node"); export(A, "alpha-token", "original-node");
        expect(A+"/v2/assistants/assistant/skills/dialog", "alpha-token", HttpMethod.POST, "{\"status\":\"Processing\"}");
        expect(A+"/v2/assistants/assistant/skills/dialog", "alpha-token", HttpMethod.GET, "{\"status\":\"Available\"}");
        export(A, "alpha-token", "edited-node");
        var op = writes.prepare(owner, projectA, new SkillWriteDtos.Prepare(UUID.fromString(targetA), UUID.fromString(source.id())), "isolated-write-key");
        var binding = json.readValue(ledger.get(op.id()).bindingJson(), SkillWriteDtos.Binding.class);
        assertThat(binding.connection().integrationId()).isEqualTo(integrationA);
        assertThat(writes.apply(owner, op.id()).state()).isEqualTo("PROCESSING");
        integrations.deactivateTarget(owner, targetA);
        assertThat(writes.reconcile(owner, op.id()).state()).isEqualTo("SUCCEEDED"); server.verify();
    }
    @Test void csrfOwnershipInvalidEndpointsAndPartialCredentialsAreEnforced() throws Exception {
        String id = portfolios.create(owner, new PortfolioDtos.Create("Empty", null)).id();
        String url = "/api/v1/portfolios/"+id+"/integrations";
        String body = json.writeValueAsString(Map.of("name","IBM","serviceUrl",A,"apiKey","fake-key"));
        mvc.perform(post(url).with(authentication(auth(owner))).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mvc.perform(post(url).with(authentication(auth(other))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
        mvc.perform(post(url).with(authentication(auth(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.replace(A,"https://localhost"))).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(authentication(auth(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"IBM\",\"serviceUrl\":\""+A+"\"}")).andExpect(status().isBadRequest());
        mvc.perform(post(url).with(authentication(auth(owner))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("$.apiKey").doesNotExist());
        mvc.perform(put("/api/v1/integrations/"+integrationA+"/credentials").with(authentication(auth(other))).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"fake-next\"}")).andExpect(status().isNotFound());
        server.verify();
    }
    private void iam(String key, String token) {
        server.expect(requestTo("https://iam.cloud.ibm.com/identity/token")).andExpect(method(HttpMethod.POST))
                .andExpect(request -> assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("apikey="+key)))
                .andRespond(withSuccess("{\"access_token\":\""+token+"\",\"expiration\":"+Instant.now().plusSeconds(3600).getEpochSecond()+"}", MediaType.APPLICATION_JSON));
    }
    private void probeReads(String base, String token) {
        for (String path : List.of("/v1/workspaces?version=2021-11-27&page_limit=1", "/v2/assistants?version=2024-08-25&page_limit=1"))
            server.expect(requestTo(base+path)).andExpect(header(HttpHeaders.AUTHORIZATION,"Bearer "+token)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
    }
    private void export(String base, String token, String node) {
        expect(base+"/v2/assistants/assistant/skills_export", token, HttpMethod.GET,
                "{\"assistant_skills\":[{\"skill_id\":\"dialog\",\"type\":\"dialog\",\"workspace\":{\"dialog_nodes\":[{\"dialog_node\":\""+node+"\"}]}}]}");
    }
    private void expect(String path, String token, HttpMethod method, String response) {
        server.expect(requestTo(path+"?version=2024-08-25")).andExpect(method(method)).andExpect(header(HttpHeaders.AUTHORIZATION,"Bearer "+token)).andRespond(withSuccess(response,MediaType.APPLICATION_JSON));
    }
    private AccountPrincipal user() { var u = users.saveAndFlush(new AccountUser("Owner", UUID.randomUUID()+"@example.invalid", "unused", false)); return new AccountPrincipal(u.getId(),u.getAuthVersion()); }
    private TargetConfig config() { return new TargetConfig("Draft",EnvironmentType.DRAFT,"assistant","draft",null,"dialog","action","2021-11-27","2024-08-25"); }
    private org.springframework.security.core.Authentication auth(AccountPrincipal actor) { return org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(actor,null,List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_USER"))); }
}
