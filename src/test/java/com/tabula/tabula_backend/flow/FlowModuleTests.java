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
    @MockitoBean FlowLayoutStore layouts;
    @Autowired FlowLayoutService layoutService;
    @Autowired FlowDiagramService diagrams;
    @Autowired DialogNodeEditService nodeEdits;
    @Autowired EditorVersionService editor;
    @Autowired ClassicDialogTranslator translator;
    @Autowired org.springframework.jdbc.core.JdbcTemplate sql;
    @Autowired SkillWriteService writes;
    @MockitoBean DiagramDocumentStore diagramDocuments;
    @MockitoBean com.tabula.tabula_backend.integration.repository.SkillWriteDocuments writeDocuments;
    @MockitoBean com.tabula.tabula_backend.integration.client.IbmSkillWriteGateway writeGateway;
    private final Map<String,com.tabula.tabula_backend.flow.dto.EditorDtos.Document> editorDocuments=new ConcurrentHashMap<>();
    private final Map<String, com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.Stored> layoutData = new ConcurrentHashMap<>();
    private final Map<String, Snapshot> stored = new ConcurrentHashMap<>();
    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private AccountUser owner, other, admin;
    private String projectId, portfolioId;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        cleanup(); stored.clear(); drafts.clear(); layoutData.clear();
        editorDocuments.clear();
        doAnswer(call -> { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var d=(com.tabula.tabula_backend.flow.dto.EditorDtos.Document)call.getArgument(0); editorDocuments.put(d.id(),d); return null;
        }).when(diagramDocuments).put(any());
        when(diagramDocuments.find(anyString(),anyString())).thenAnswer(call -> Optional.ofNullable(editorDocuments.get(call.getArgument(1))));
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        when(layouts.find(anyString(), anyString())).thenAnswer(call -> Optional.ofNullable(layoutData.get(call.getArgument(1))));
        when(layouts.save(anyString(), anyString(), nullable(Long.class), anyString(), anyString(), anyString())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String id = call.getArgument(1); Long revision = call.getArgument(2);
            var previous = layoutData.get(id);
            if (revision == null ? previous != null : previous == null || previous.revision() != revision) throw new FlowException(HttpStatus.PRECONDITION_FAILED, "LAYOUT_REVISION_MISMATCH", "Mismatch");
            var saved = new com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.Stored(id, call.getArgument(0), revision == null ? 1 : revision + 1,
                    call.getArgument(3), call.getArgument(4), call.getArgument(5), java.time.Instant.now().toString());
            layoutData.put(id, saved); return saved;
        });
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
        sql.update("DELETE FROM ibm_skill_write_locks"); sql.update("DELETE FROM ibm_skill_writes");
        sql.update("DELETE FROM diagram_versions"); sql.update("DELETE FROM diagram_flows");
        outbox.deleteAll(); versions.deleteAll(); flows.deleteAll(); targets.deleteAll(); integrationRepository.deleteAll();
        projects.deleteAll(); members.deleteAll(); portfolioRepository.deleteAll(); users.deleteAll();
    }

    private static final String LAYOUT = "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"node_1\",\"position\":{\"x\":12.5,\"y\":-20},\"collapsed\":false}],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";

    private static final String EDITABLE_RAW = """
        {"type":"dialog","future_skill":{"kept":true},"workspace":{"future_workspace":{"kept":true},"dialog_nodes":[
          {"dialog_node":"node_1","title":"Original","conditions":"#old","metadata":{"$unknown.key":1234567890.1234567890123456789},
           "output":{"unknown_output":true,"generic":[
             {"response_type":"text","selection_policy":"random","values":[{"text":"Old text","future":{"kept":true}},{"text":"Other text"}]},
             {"response_type":"image","source":"https://example.invalid/image"}]}},
          {"dialog_node":"node_2","previous_sibling":"node_1","conditions":"true"}
        ]}}
        """;
    private VersionView editableDraft(String key) {
        var v = service.upload(actor(owner), projectId, new Upload("Editable", FlowType.DIALOG, EDITABLE_RAW), key);
        service.startDraft(actor(owner), v.flowId(), v.id()); return v;
    }
    @Test void visualLayoutNodesRoundTripWithoutChangingDraftOrVersion() throws Exception {
        var v = editableDraft("visual-layout-nodes");
        String url = "/api/v1/flows/" + v.flowId() + "/layout";
        String raw = """
            {"schemaVersion":1,"nodes":[
              {"id":"visual:11111111-1111-4111-8111-111111111111","position":{"x":1,"y":2},"visualType":"text"},
              {"id":"visual:22222222-2222-4222-8222-222222222222","position":{"x":3,"y":4},"visualType":"generative"},
              {"id":"visual:33333333-3333-4333-8333-333333333333","position":{"x":5,"y":6},"visualType":"options"}
            ],"viewport":{"x":0,"y":0,"zoom":1}}
            """;
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-None-Match", "*").contentType("application/json").content(raw))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.nodes[1].visualType").value("generative"));
        mvc.perform(get(url).with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.nodes[2].visualType").value("options"));
        var draft = diagrams.draft(actor(owner), v.flowId());
        var version = diagrams.version(actor(owner), v.id());
        assertThat(draft.nodes().stream().filter(n -> "visual".equals(n.type())).map(n -> n.data().kind())).containsExactlyInAnyOrder("text", "generative", "options");
        assertThat(version.nodes().stream().filter(n -> "visual".equals(n.type()))).hasSize(3).allMatch(n -> n.data().layoutOnly() && !n.data().editable());
        assertThat(service.draft(actor(owner), v.flowId()).rawJson()).isEqualTo(EDITABLE_RAW);
        assertThat(service.export(actor(owner), v.id())).isEqualTo(EDITABLE_RAW);
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-Match", "\"layout-1\"").contentType("application/json").content(raw.replace("\"visualType\":\"text\"", "\"visualType\":\"unknown\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-Match", "\"layout-1\"").contentType("application/json").content(raw.replace("visual:11111111-1111-4111-8111-111111111111", "dialog:collision")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ibm);
    }

    @Test void nodeCommandPreservesUnknownFieldsPrecisionAndOriginalVersion() throws Exception {
        var v = editableDraft("node-edit-preservation");
        layoutService.save(actor(owner), v.flowId(), null, "*", LAYOUT);
        String body = """
            {"nodeId":"node_1","title":"Novo titulo","conditions":"#new && @sys-number",
             "textEdits":[{"responseIndex":0,"valueIndex":0,"text":"Nova resposta"}]}
            """;
        mvc.perform(patch("/api/v1/flows/"+v.flowId()+"/draft/dialog-nodes").with(as(owner)).with(csrf()).header("If-Match", "\"1\"").contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.workspace.dialog_nodes[0].title").value("Novo titulo"))
                .andExpect(jsonPath("$.workspace.dialog_nodes[0].output.generic[0].values[0].text").value("Nova resposta"));
        String expected = EDITABLE_RAW.replace("\"Original\"", "\"Novo titulo\"").replace("#old", "#new && @sys-number").replace("Old text", "Nova resposta");
        assertThat(codec.checksum(service.draft(actor(owner), v.flowId()).rawJson())).isEqualTo(codec.checksum(expected));
        assertThat(service.export(actor(owner), v.id())).isEqualTo(EDITABLE_RAW);
        assertThat(layoutService.find(actor(owner), v.flowId()).revision()).isEqualTo(1);
        assertThat(diagrams.draft(actor(owner), v.flowId()).readOnly()).isFalse();
        assertThat(diagrams.version(actor(owner), v.id()).readOnly()).isTrue();
        verifyNoInteractions(ibm);
    }
    @Test void nodeCommandRejectsStaleRevisionsAndNeverPersistsPartialChanges() throws Exception {
        var v = editableDraft("node-edit-revisions"); String url = "/api/v1/flows/"+v.flowId()+"/draft/dialog-nodes";
        String body = "{\"nodeId\":\"node_1\",\"title\":\"Novo\"}";
        mvc.perform(patch(url).with(as(owner)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isPreconditionRequired());
        nodeEdits.edit(actor(owner), v.flowId(), "\"1\"", body);
        mvc.perform(patch(url).with(as(owner)).with(csrf()).header("If-Match", "\"1\"").contentType("application/json").content(body)).andExpect(status().isPreconditionFailed());
        String before = service.draft(actor(owner), v.flowId()).rawJson();
        assertThatThrownBy(() -> nodeEdits.edit(actor(owner), v.flowId(), "\"2\"", """
            {"nodeId":"node_1","title":"Nao deve salvar","textEdits":[{"responseIndex":999,"valueIndex":0,"text":"No"}]}
            """)).isInstanceOf(FlowException.class);
        assertThat(service.draft(actor(owner), v.flowId()).rawJson()).isEqualTo(before);
        assertThat(service.draft(actor(owner), v.flowId()).revision()).isEqualTo(2);
    }
    @Test void nodeCommandValidatesFieldsResponseTypesAndDuplicateIds() {
        var v = editableDraft("node-edit-validation");
        for (String body : List.of("{\"nodeId\":\"node_1\"}", "{\"nodeId\":\"node_1\",\"title\":null}",
                "{\"nodeId\":\"node_1\",\"parent\":\"node_2\"}", "{\"nodeId\":\"node_1\",\"title\":\"bad!\"}",
                "{\"nodeId\":\"node_1\",\"conditions\":\"\"}", "{\"nodeId\":\"node_1\",\"textEdits\":[]}",
                "{\"nodeId\":\"node_1\",\"textEdits\":[{\"responseIndex\":1,\"valueIndex\":0,\"text\":\"No\"}]}",
                "{\"nodeId\":\"node_1\",\"textEdits\":[{\"responseIndex\":0.5,\"valueIndex\":0,\"text\":\"No\"}]}",
                "{\"nodeId\":\"missing\",\"title\":\"No\"}")) {
            assertThatThrownBy(() -> nodeEdits.edit(actor(owner), v.flowId(), "\"1\"", body)).isInstanceOf(FlowException.class);
        }
        assertThat(service.draft(actor(owner), v.flowId()).revision()).isEqualTo(1);
        service.updateDraft(actor(owner), v.flowId(), "\"1\"", EDITABLE_RAW.replace("\"node_2\"", "\"node_1\""));
        assertThatThrownBy(() -> nodeEdits.edit(actor(owner), v.flowId(), "\"2\"", "{\"nodeId\":\"node_1\",\"title\":\"No\"}"))
                .isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("DIALOG_NODE_AMBIGUOUS"));
    }
    @Test void nodeCommandBlocksComplexNodesDynamicTextsAndActionDocuments() {
        var v = editableDraft("node-edit-unsupported");
        String title = "{\"nodeId\":\"node_1\",\"title\":\"Novo\"}";
        service.updateDraft(actor(owner), v.flowId(), "\"1\"", EDITABLE_RAW.replace("\"dialog_node\":\"node_1\"", "\"dialog_node\":\"node_1\",\"type\":\"frame\""));
        assertThatThrownBy(() -> nodeEdits.edit(actor(owner), v.flowId(), "\"2\"", title)).isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("DIALOG_NODE_EDIT_UNSUPPORTED"));
        service.updateDraft(actor(owner), v.flowId(), "\"2\"", EDITABLE_RAW.replace("\"text\":\"Old text\"", "\"text\":\"Old text\",\"text_expression\":{\"concat\":[]}"));
        String textEdit = "{\"nodeId\":\"node_1\",\"textEdits\":[{\"responseIndex\":0,\"valueIndex\":0,\"text\":\"No\"}]}";
        assertThatThrownBy(() -> nodeEdits.edit(actor(owner), v.flowId(), "\"3\"", textEdit)).isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("TEXT_RESPONSE_UNSUPPORTED"));
        service.updateDraft(actor(owner), v.flowId(), "\"3\"", EDITABLE_RAW);
        String duplicate = "{\"nodeId\":\"node_1\",\"textEdits\":[{\"responseIndex\":0,\"valueIndex\":0,\"text\":\"One\"},{\"responseIndex\":0,\"valueIndex\":0,\"text\":\"Two\"}]}";
        assertThatThrownBy(() -> nodeEdits.edit(actor(owner), v.flowId(), "\"4\"", duplicate)).isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("DUPLICATE_TEXT_EDIT"));
        var action = service.upload(actor(owner), projectId, new Upload("Action", FlowType.ACTION, "{\"type\":\"action\",\"workspace\":{\"actions\":[]}}"), "action-unsupported-key");
        service.startDraft(actor(owner), action.flowId(), action.id());
        assertThatThrownBy(() -> nodeEdits.edit(actor(owner), action.flowId(), "\"1\"", title)).isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("DIALOG_EDIT_REQUIRED"));
    }
    @Test void nodeCommandEnforcesScopeRoleCsrfAndArchive() throws Exception {
        var v = editableDraft("node-edit-permissions"); String url = "/api/v1/flows/"+v.flowId()+"/draft/dialog-nodes";
        String body = "{\"nodeId\":\"node_1\",\"title\":\"Novo\"}";
        mvc.perform(patch(url).with(as(owner)).header("If-Match", "\"1\"").contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(patch(url).with(as(other)).with(csrf()).header("If-Match", "\"1\"").contentType("application/json").content(body)).andExpect(status().isNotFound());
        portfolios.addMember(actor(owner), portfolioId, new PortfolioDtos.AddMember(UUID.fromString(other.getId()), PortfolioRole.VIEWER));
        mvc.perform(patch(url).with(as(other)).with(csrf()).header("If-Match", "\"1\"").contentType("application/json").content(body)).andExpect(status().isForbidden());
        portfolios.changeRole(actor(owner), portfolioId, other.getId(), new PortfolioDtos.ChangeRole(PortfolioRole.EDITOR));
        mvc.perform(patch(url).with(as(other)).with(csrf()).header("If-Match", "\"1\"").contentType("application/json").content(body)).andExpect(status().isOk());
        metadata.archive(actor(owner), v.flowId());
        mvc.perform(patch(url).with(as(owner)).with(csrf()).header("If-Match", "\"2\"").contentType("application/json").content(body)).andExpect(status().isConflict());
    }

    @Test void diagramsAreReadOnlyScopedAndExposeExactSourceRevisions() throws Exception {
        var version = upload("diagram-source-key");
        String url = "/api/v1/flows/" + version.flowId() + "/diagram";
        mvc.perform(get(url).with(as(owner))).andExpect(status().isNotFound());
        service.startDraft(actor(owner), version.flowId(), version.id());
        mvc.perform(get(url).with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.draftRevision").value(1))
                .andExpect(jsonPath("$.readOnly").value(true)).andExpect(jsonPath("$.sourceChecksum").value(version.checksum()));
        mvc.perform(get("/api/v1/versions/" + version.id() + "/diagram").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.versionId").value(version.id()));
        mvc.perform(get(url).with(as(other))).andExpect(status().isNotFound());
        portfolios.addMember(actor(owner), portfolioId, new PortfolioDtos.AddMember(UUID.fromString(other.getId()), PortfolioRole.VIEWER));
        mvc.perform(get(url).with(as(other))).andExpect(status().isOk());
        assertThat(drafts.get(version.flowId()).revision()).isEqualTo(1);
        assertThat(service.export(actor(owner), version.id())).isEqualTo(RAW);
        verifyNoInteractions(ibm);
    }
    @Test void diagramDoesNotHideStorageOutagesOrCorruptedDrafts() {
        var v = upload("diagram-integrity-key"); service.startDraft(actor(owner), v.flowId(), v.id());
        doThrow(new FlowException(HttpStatus.SERVICE_UNAVAILABLE, "LAYOUT_STORAGE_UNAVAILABLE", "unavailable")).when(layouts).find(anyString(), anyString());
        assertThatThrownBy(() -> diagrams.draft(actor(owner), v.flowId())).isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("LAYOUT_STORAGE_UNAVAILABLE"));
        var d = drafts.get(v.flowId()); drafts.put(v.flowId(), new Draft(d.flowId(), d.portfolioId(), d.revision(), d.checksum(), "{}"));
        assertThatThrownBy(() -> diagrams.draft(actor(owner), v.flowId())).isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("FLOW_INTEGRITY_ERROR"));
    }

    @Test void layoutsHaveIndependentEtagsAndNeverChangeIbmJson() throws Exception {
        var v = upload("layout-independent-key");
        service.startDraft(actor(owner), v.flowId(), v.id());
        String url = "/api/v1/flows/" + v.flowId() + "/layout";
        mvc.perform(get(url).with(as(owner))).andExpect(status().isNotFound());
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-None-Match", "*").contentType("application/json").content(LAYOUT))
                .andExpect(status().isCreated()).andExpect(header().string("ETag", "\"layout-1\"")).andExpect(jsonPath("$.nodes[0].position.x").value(12.5));
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-Match", "\"layout-1\"").contentType("application/json").content(LAYOUT.replace("12.5", "99")))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"layout-2\""));
        mvc.perform(get(url).with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.nodes[0].position.x").value(99));
        assertThat(service.export(actor(owner), v.id())).isEqualTo(RAW);
        assertThat(service.draft(actor(owner), v.flowId()).revision()).isEqualTo(1);
        assertThat(service.draft(actor(owner), v.flowId()).rawJson()).isEqualTo(RAW);
        verifyNoInteractions(ibm);
    }
    @Test void layoutCreationAndUpdatesRequireExplicitPreconditions() throws Exception {
        String id = upload("layout-preconditions-key").flowId();
        String url = "/api/v1/flows/" + id + "/layout";
        mvc.perform(put(url).with(as(owner)).with(csrf()).contentType("application/json").content(LAYOUT)).andExpect(status().isPreconditionRequired());
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-Match", "\"1\"").contentType("application/json").content(LAYOUT)).andExpect(status().isBadRequest());
        layoutService.save(actor(owner), id, null, "*", LAYOUT);
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-None-Match", "*").contentType("application/json").content(LAYOUT)).andExpect(status().isPreconditionFailed());
        layoutService.save(actor(owner), id, "\"layout-1\"", null, LAYOUT);
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-Match", "\"layout-1\"").contentType("application/json").content(LAYOUT)).andExpect(status().isPreconditionFailed());
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-Match", "\"layout-2\"").header("If-None-Match", "*").contentType("application/json").content(LAYOUT)).andExpect(status().isBadRequest());
    }
    @Test void layoutPermissionsIncludeScopeCsrfAndArchiveProtection() throws Exception {
        String id = upload("layout-permissions-key").flowId();
        layoutService.save(actor(owner), id, null, "*", LAYOUT);
        String url = "/api/v1/flows/" + id + "/layout";
        mvc.perform(get(url).with(as(other))).andExpect(status().isNotFound());
        portfolios.addMember(actor(owner), portfolioId, new PortfolioDtos.AddMember(UUID.fromString(other.getId()), PortfolioRole.VIEWER));
        mvc.perform(get(url).with(as(other))).andExpect(status().isOk());
        mvc.perform(put(url).with(as(other)).with(csrf()).header("If-Match", "\"layout-1\"").contentType("application/json").content(LAYOUT)).andExpect(status().isForbidden());
        mvc.perform(put(url).with(as(owner)).header("If-Match", "\"layout-1\"").contentType("application/json").content(LAYOUT)).andExpect(status().isForbidden());
        metadata.archive(actor(owner), id);
        mvc.perform(get(url).with(as(owner))).andExpect(status().isOk());
        mvc.perform(put(url).with(as(owner)).with(csrf()).header("If-Match", "\"layout-1\"").contentType("application/json").content(LAYOUT)).andExpect(status().isConflict());
    }
    @Test void invalidVisualGroupsAndSemanticPayloadsAreRejected() {
        String id = upload("layout-validation-key").flowId();
        for (String raw : List.of(
                "{\"schemaVersion\":2,\"nodes\":[]}",
                "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[]}",
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"a\",\"position\":{\"x\":0,\"y\":0},\"parentId\":\"missing\"}]}",
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"a\",\"position\":{\"x\":0,\"y\":0},\"parentId\":\"a\"}]}",
                "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"a\",\"position\":{\"x\":0,\"y\":0}},{\"id\":\"a\",\"position\":{\"x\":1,\"y\":1}}]}",
                LAYOUT.replace("12.5", "1e309"), LAYOUT.replace("\"zoom\":1", "\"zoom\":0"), LAYOUT.replace("\"collapsed\":false", "\"width\":-1"))) {
            assertThatThrownBy(() -> layoutService.save(actor(owner), id, null, "*", raw)).isInstanceOf(FlowException.class);
        }
        verify(layouts, never()).save(anyString(), anyString(), any(), anyString(), anyString(), anyString());
    }
    @Test void corruptedStoredLayoutIsNotReturned() {
        String id = upload("layout-integrity-key").flowId();
        layoutService.save(actor(owner), id, null, "*", LAYOUT);
        var s = layoutData.get(id);
        layoutData.put(id, new com.tabula.tabula_backend.flow.dto.FlowLayoutDtos.Stored(id, portfolioId, s.revision(), "{}", s.checksum(), s.updatedBy(), s.updatedAt()));
        assertThatThrownBy(() -> layoutService.find(actor(owner), id)).isInstanceOfSatisfying(FlowException.class, ex -> assertThat(ex.code()).isEqualTo("LAYOUT_INTEGRITY_ERROR"));
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
        String integration=integrations.create(actor(admin),portfolioId,new CreateIntegration("IBM")).id();
        UUID targetId = UUID.fromString(integrations.createTarget(actor(owner),projectId,new CreateTarget(UUID.fromString(integration),
                new TargetConfig("Draft",EnvironmentType.DRAFT,"assistant","environment",null,"dialog",null,"2021-11-27","2024-08-25"))).id());
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
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version.number").value(1));
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
    @Test void legacyLocalUploadRemainsReadableUntilExplicitEditorMigration() throws Exception {
        var input=new Upload("Canvas",FlowType.LOCAL,EditorVersionService.EMPTY);
        var version=service.upload(actor(owner),projectId,input,"local-create-001");
        String flow = version.flowId();
        assertThat(metadata.find(actor(owner), flow).type()).isEqualTo(FlowType.LOCAL);
        assertThat(service.upload(actor(owner),projectId,input,"local-create-001").id()).isEqualTo(version.id());
        mvc.perform(post("/api/v1/flows/" + flow + "/draft").with(as(owner)).with(csrf())
                .contentType("application/json").content("{\"versionId\":\"" + version.id() + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        String canvas = """
                {"schemaVersion":1,"nodes":[
                  {"id":"a","type":"text","position":{"x":10,"y":20},"data":{"text":"Olá","custom":{"$key.with.dot":42}}},
                  {"id":"b","position":{"x":300,"y":20},"data":{"label":"Fim"}}],
                 "edges":[{"id":"a-b","source":"a","target":"b","sourceHandle":"yes","data":{"label":"Sim"}}],
                 "viewport":{"x":10,"y":15,"zoom":0.75}}
                """;
        mvc.perform(put("/api/v1/flows/" + flow + "/draft").with(as(owner)).with(csrf())
                .header("If-Match", "\"1\"").contentType("application/json").content(canvas))
                .andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.edges[0].sourceHandle").value("yes"));
        mvc.perform(put("/api/v1/flows/" + flow + "/draft").with(as(owner)).with(csrf())
                .header("If-Match", "\"1\"").contentType("application/json").content(canvas)).andExpect(status().isPreconditionFailed());
        var saved = mvc.perform(post("/api/v1/flows/" + flow + "/versions").with(as(owner)).with(csrf())
                .header("If-Match", "\"2\"").header("Idempotency-Key", "local-snapshot-002"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(2)).andReturn();
        var snapshot = json.readValue(saved.getResponse().getContentAsString(), VersionView.class);
        assertThat(service.export(actor(owner), snapshot.id())).isEqualTo(canvas);
        assertThat(service.export(actor(owner), version.id())).contains("\"nodes\":[]");
        mvc.perform(get("/api/v1/flows/" + flow + "/draft").with(as(other))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/flows/" + flow + "/diagram").with(as(owner))).andExpect(status().isUnprocessableEntity());
        assertThatThrownBy(() -> layoutService.save(actor(owner), flow, null, "*", "{}"))
                .isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> service.importIbm(actor(owner), projectId,
                new IbmImport(UUID.randomUUID(), FlowType.DIALOG, null, UUID.fromString(flow)), "local-import-003"))
                .isInstanceOf(FlowException.class);
        assertThatThrownBy(() -> service.importIbm(actor(owner), projectId,
                new IbmImport(UUID.randomUUID(), FlowType.LOCAL, null, null), "local-import-004"))
                .isInstanceOf(FlowException.class);
        verifyNoInteractions(ibm);
    }

    @Test void localCanvasRejectsBrokenGraphAndParentCycles() {
        String empty = "{\"schemaVersion\":1,\"nodes\":[],\"edges\":[],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
        assertThat(codec.validate(empty, FlowType.LOCAL)).hasSize(64);
        for (String invalid : List.of(empty.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                empty.replace("\"edges\":[]", "\"edges\":[{\"id\":\"e\",\"source\":\"missing\",\"target\":\"missing\"}]"),
                empty.replace("\"nodes\":[]", "\"nodes\":[{\"id\":\"a\",\"parentId\":\"a\",\"position\":{\"x\":0,\"y\":0},\"data\":{}}]"),
                empty.replace("\"zoom\":1", "\"zoom\":0"))) {
            assertThatThrownBy(() -> codec.validate(invalid, FlowType.LOCAL)).isInstanceOf(FlowException.class);
        }
    }

    @Test void editableVersionHttpLifecycleDuplicatesCompleteCanvasAndHasNoDraft() throws Exception {
        var result=mvc.perform(post("/api/v1/projects/"+projectId+"/flows").with(as(owner)).with(csrf())
                .header("Idempotency-Key","editor-create-001").contentType("application/json").content("{\"name\":\"Editor\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.version.number").value(1))
                .andExpect(jsonPath("$.version.name").value("Versão 1")).andReturn();
        var created=json.readValue(result.getResponse().getContentAsString(),com.tabula.tabula_backend.flow.dto.EditorDtos.Created.class);
        String id=created.versionId(),flow=created.flowId();
        assertThat(editor.create(actor(owner),projectId,new com.tabula.tabula_backend.flow.dto.EditorDtos.Create("Editor",null),"editor-create-001").versionId()).isEqualTo(id);
        assertThat(metadata.find(actor(owner),flow).editorManaged()).isTrue();
        String canvas=EditorVersionService.EMPTY.replace("\"nodes\":[]","\"nodes\":[{\"id\":\"a\",\"type\":\"text\",\"position\":{\"x\":55,\"y\":88},\"data\":{\"text\":\"Olá\",\"conditions\":\"welcome\"}}]")
                .replace("\"variables\":{}","\"variables\":{\"counter\":123456789012345678901234567890}");
        mvc.perform(put("/api/v1/versions/"+id+"/canvas").with(as(owner)).with(csrf()).header("If-Match","\"1\"")
                .contentType("application/json").content(canvas)).andExpect(status().isOk()).andExpect(header().string("ETag","\"2\""));
        var copy=mvc.perform(post("/api/v1/flows/"+flow+"/versions").with(as(owner)).with(csrf())
                .header("Idempotency-Key","editor-copy-002").contentType("application/json")
                .content("{\"name\":\"Cópia\",\"sourceVersionId\":\""+id+"\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.number").value(2)).andReturn();
        var duplicate=json.readValue(copy.getResponse().getContentAsString(),com.tabula.tabula_backend.flow.dto.EditorDtos.Version.class);
        assertThat(editor.read(actor(owner),duplicate.id()).rawJson()).isEqualTo(canvas);
        editor.save(actor(owner),id,"\"2\"",EditorVersionService.EMPTY);
        assertThat(editor.read(actor(owner),duplicate.id()).rawJson()).isEqualTo(canvas);
        mvc.perform(patch("/api/v1/versions/"+duplicate.id()).with(as(owner)).with(csrf()).header("If-Match","\"1\"")
                .contentType("application/json").content("{\"name\":\"Homologação\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Homologação")).andExpect(header().string("ETag","\"2\""));
        var empty=editor.add(actor(owner),flow,new com.tabula.tabula_backend.flow.dto.EditorDtos.NewVersion("Vazia",null),"editor-empty-003");
        assertThat(empty.number()).isEqualTo(3); assertThat(editor.read(actor(owner),empty.id()).rawJson()).isEqualTo(EditorVersionService.EMPTY);
        mvc.perform(get("/api/v1/flows/"+flow+"/versions").with(as(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(3));
        mvc.perform(get("/api/v1/flows/"+flow+"/draft").with(as(owner))).andExpect(status().isGone());
        mvc.perform(get("/api/v1/versions/"+id+"/canvas").with(as(other))).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/versions/"+id+"/canvas").with(as(owner)).header("If-Match","\"3\"")
                .contentType("application/json").content(canvas)).andExpect(status().isForbidden());
        assertThat(versions.count()).isZero(); assertThat(drafts).isEmpty(); verifyNoInteractions(documents,ibm);
    }

    @Test void concurrentVersionSavesHaveOneWinnerAndMongoFailureNeverChangesSqlPointer() throws Exception {
        var created=editor.create(actor(owner),projectId,new com.tabula.tabula_backend.flow.dto.EditorDtos.Create("Editor",null),"editor-create-001");
        var start=new java.util.concurrent.CountDownLatch(1);
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(int i=0;i<2;i++) results.add(executor.submit(()->{
                start.await();
                try { editor.save(actor(owner),created.versionId(),"\"1\"",EditorVersionService.EMPTY); return true; }
                catch(FlowException ex) { assertThat(ex.code()).isEqualTo("VERSION_REVISION_MISMATCH"); return false; }
            }));
            start.countDown(); assertThat(List.of(results.get(0).get(),results.get(1).get())).containsExactlyInAnyOrder(true,false);
        }
        var before=editor.read(actor(owner),created.versionId());
        doThrow(new FlowException(HttpStatus.SERVICE_UNAVAILABLE,"DIAGRAM_STORAGE_UNAVAILABLE","Unavailable")).when(diagramDocuments).put(any());
        assertThatThrownBy(()->editor.save(actor(owner),created.versionId(),"\"2\"",EditorVersionService.EMPTY)).isInstanceOf(FlowException.class);
        assertThat(editor.read(actor(owner),created.versionId())).isEqualTo(before);
    }

    @Test void linkedCanvasImportsExplicitlyAndPreparesFrozenClassicDialogPublication() throws Exception {
        String integration=integrations.create(actor(admin),portfolioId,new CreateIntegration("IBM")).id();
        String target=integrations.createTarget(actor(owner),projectId,new CreateTarget(UUID.fromString(integration),
                new TargetConfig("Draft",EnvironmentType.DRAFT,"assistant","environment",null,"dialog",null,"2021-11-27","2024-08-25"))).id();
        var created=editor.create(actor(owner),projectId,new com.tabula.tabula_backend.flow.dto.EditorDtos.Create("Editor",null),"editor-create-001");
        editor.bind(actor(owner),created.flowId(),target); verifyNoInteractions(ibm);
        Map<String,Object> skill=Map.of("type","dialog","custom",Map.of("kept",true),"workspace",Map.of("intents",List.of(),"dialog_nodes",List.of(
                Map.of("dialog_node","hello","type","standard","conditions","welcome","output",Map.of("generic",List.of(Map.of("response_type","text","values",List.of(Map.of("text","Olá")))))))));
        when(ibm.export(actor(owner),projectId,target,"DIALOG")).thenReturn(skill);
        var imported=editor.importIbm(actor(owner),created.versionId(),"\"1\"");
        assertThat(imported.version().revision()).isEqualTo(2); assertThat(imported.rawJson()).contains("ibmDialog","hello","custom");
        when(ibm.exportSkill("assistant","dialog","2024-08-25","DIALOG")).thenReturn(skill);
        when(writeGateway.update(any(),anyMap())).thenReturn(Map.of("status","Processing"));
        var writeCopies=new ConcurrentHashMap<String,com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Documents>();
        doAnswer(call->{var d=(com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Documents)call.getArgument(0); writeCopies.put(d.id(),d); return null;}).when(writeDocuments).put(any());
        when(writeDocuments.get(anyString(),anyString())).thenAnswer(call->writeCopies.get(call.getArgument(1)));
        assertThatThrownBy(()->editor.publish(actor(owner),created.versionId(),"\"2\"","editor-publish-001",false)).isInstanceOf(FlowException.class);
        var prepared=editor.publish(actor(owner),created.versionId(),"\"2\"","editor-publish-001",true);
        assertThat(prepared.state()).isEqualTo("READY");
        editor.save(actor(owner),created.versionId(),"\"2\"",EditorVersionService.EMPTY);
        assertThat(writes.apply(actor(owner),prepared.id()).state()).isEqualTo("PROCESSING");
        verify(writeGateway).update(any(),argThat(workspace -> workspace.toString().contains("hello")));
        assertThat(writeCopies.get(prepared.id()).candidateJson()).contains("hello");
    }

    @Test void legacyMigrationPreservesOldVersionsLayoutAndWorkingContentWithoutFurtherDrafts() {
        var old=upload("legacy-create-001"); service.startDraft(actor(owner),old.flowId(),old.id());
        String changed=RAW.replace("\"dialog_nodes\":[]","\"dialog_nodes\":[{\"dialog_node\":\"new\",\"conditions\":\"true\"}]");
        service.updateDraft(actor(owner),old.flowId(),"\"1\"",changed);
        var migrated=editor.migrate(actor(owner),old.flowId());
        assertThat(migrated).hasSize(2); assertThat(migrated.get(1).name()).isEqualTo("Trabalho anterior");
        assertThat(editor.read(actor(owner),migrated.get(1).id()).rawJson()).contains("new");
        assertThat(service.export(actor(owner),old.id())).isEqualTo(RAW);
        assertThatThrownBy(()->service.draft(actor(owner),old.flowId())).isInstanceOfSatisfying(FlowException.class,ex->assertThat(ex.code()).isEqualTo("FLOW_DRAFT_REMOVED"));
        assertThat(editor.migrate(actor(owner),old.flowId())).hasSize(2);
    }

    @Test void editorRejectsCrossFlowDuplicationViewerWritesAndArchivedChanges() throws Exception {
        var a=editor.create(actor(owner),projectId,new com.tabula.tabula_backend.flow.dto.EditorDtos.Create("A",null),"editor-create-001");
        var b=editor.create(actor(owner),projectId,new com.tabula.tabula_backend.flow.dto.EditorDtos.Create("B",null),"editor-create-002");
        assertThatThrownBy(()->editor.add(actor(owner),b.flowId(),new com.tabula.tabula_backend.flow.dto.EditorDtos.NewVersion("Invalid",UUID.fromString(a.versionId())),"editor-copy-cross"))
                .isInstanceOfSatisfying(FlowException.class,ex->assertThat(ex.code()).isEqualTo("FLOW_VERSION_MISMATCH"));
        portfolios.addMember(actor(owner),portfolioId,new PortfolioDtos.AddMember(UUID.fromString(other.getId()),PortfolioRole.VIEWER));
        mvc.perform(get("/api/v1/versions/"+a.versionId()+"/canvas").with(as(other))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/versions/"+a.versionId()+"/canvas").with(as(other)).with(csrf()).header("If-Match","\"1\"")
                .contentType("application/json").content(EditorVersionService.EMPTY)).andExpect(status().isForbidden());
        assertThatThrownBy(()->editor.publish(actor(owner),a.versionId(),"\"1\"","editor-publish-01",true))
                .isInstanceOfSatisfying(FlowException.class,ex->assertThat(ex.code()).isEqualTo("IBM_FLOW_NOT_LINKED"));
        metadata.archive(actor(owner),a.flowId());
        assertThatThrownBy(()->editor.save(actor(owner),a.versionId(),"\"1\"",EditorVersionService.EMPTY)).isInstanceOf(FlowException.class);
        assertThat(editor.read(actor(owner),a.versionId()).version().revision()).isEqualTo(1);
    }

    private VersionView upload(String key) { return service.upload(actor(owner), projectId, new Upload("Test", FlowType.DIALOG, RAW), key); }
    private AccountUser user(boolean admin) { return users.saveAndFlush(new AccountUser("Test", UUID.randomUUID() + "@example.invalid", "unused", admin)); }
    private static AccountPrincipal actor(AccountUser u) { return new AccountPrincipal(u.getId(), u.getAuthVersion()); }
    private static RequestPostProcessor as(AccountUser u) { return authentication(UsernamePasswordAuthenticationToken.authenticated(actor(u), null,
            List.of(new SimpleGrantedAuthority(u.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER")))); }
}
