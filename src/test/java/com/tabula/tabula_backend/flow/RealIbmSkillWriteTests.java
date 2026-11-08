package com.tabula.tabula_backend.flow;

import com.mongodb.client.MongoClient;
import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import com.tabula.tabula_backend.flow.repository.*;
import com.tabula.tabula_backend.flow.service.*;
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
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import static com.mongodb.client.model.Filters.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Opt-in: mutates configured IBM test Draft skills, verifies and restores original content. */
@EnabledIfSystemProperty(named = "tabula.skill.write.verify", matches = "true")
@SpringBootTest(properties = {
        "spring.config.location=file:src/main/resources/application.properties", "app.bootstrap-admin.enabled=false",
        "logging.level.root=ERROR", "spring.main.banner-mode=off",
        "spring.mongodb.connect-timeout=5s", "spring.mongodb.socket-timeout=10s"
})
class RealIbmSkillWriteTests {
    @Autowired MongoClient mongo;
    @Value("${spring.mongodb.database}") String mongoDatabase;
    @Autowired UserRepository users;
    @Autowired PortfolioService portfolios;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired IntegrationService integrations;
    @Autowired IbmIntegrationRepository integrationRepository;
    @Autowired IbmTargetRepository targets;
    @Autowired IbmSettings settings;
    @Autowired FlowService service;
    @Autowired FlowMetadataService metadata;
    @Autowired FlowRepository flows;
    @Autowired FlowVersionRepository versions;
    @Autowired FlowWriteRepository outbox;
    @Autowired FlowDocumentStore documents;
    @Autowired FlowJsonCodec json;
    @Autowired SkillWriteService skillWrites;
    @Autowired org.springframework.jdbc.core.JdbcTemplate sql;

    @Autowired IbmFlowExportService exports;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired com.tabula.tabula_backend.integration.client.IbmSkillWriteGateway writeGateway;
    @Autowired SkillWriteLedger ledger;

    @Test
    @EnabledIfSystemProperty(named = "tabula.skill.write.recover", matches = "true")
    void resumeAndRestorePreservedOperation() throws Exception {
        String id = System.getProperty("tabula.skill.write.recovery-id", "");
        UUID.fromString(id);
        var row = ledger.get(id); var v = row.view();
        var account = users.findById(v.createdBy()).orElseThrow();
        assertThat(account.getEmail()).startsWith("ibm-write-").endsWith("@example.invalid");
        var actor = new AccountPrincipal(v.createdBy(), account.getAuthVersion());
        var type = FlowType.valueOf(v.type());
        var confirmed = applyAndWait(actor, id, type);
        assertThat(confirmed.state()).as("Original operation state; code=" + confirmed.errorCode()).isEqualTo("SUCCEEDED");
        System.out.println("IBM_WRITE_RECOVERY " + type + " UPDATE_READBACK_IDEMPOTENCY OK");
        assertThat(skillWrites.apply(actor, id).state()).isEqualTo("SUCCEEDED");
        String originalId = sql.queryForObject("SELECT id FROM flow_versions WHERE project_id=? AND source_type='IBM'", String.class, v.projectId());
        var restore = retryExport(() -> skillWrites.prepare(actor, v.projectId(),
                new com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Prepare(UUID.fromString(v.targetId()), UUID.fromString(originalId)), "restore-recovery-" + id));
        var restored = applyAndWait(actor, restore.id(), type);
        assertThat(restored.state()).as("Restore state; code=" + restored.errorCode()).isEqualTo("SUCCEEDED");
        var after = retryExport(() -> exports.export(actor, v.projectId(), v.targetId(), v.type()));
        assertThat(json.checksum(json.encode(after.get("workspace")))).isEqualTo(v.baselineHash());
        System.out.println("IBM_WRITE_RECOVERY " + type + " ORIGINAL_CONTENT_RESTORED OK");
        var db = mongo.getDatabase(mongoDatabase);
        String portfolioId = portfolios.project(actor, v.projectId()).portfolioId();
        String integrationId = integrations.target(actor, v.targetId()).integrationId();
        for (var op : skillWrites.list(actor, v.projectId(), 0, 100).content()) sql.update("DELETE FROM ibm_skill_write_locks WHERE operation_id=?", op.id());
        db.getCollection("ibm_skill_write_documents").deleteMany(eq("projectId", v.projectId()));
        sql.update("DELETE FROM ibm_skill_writes WHERE project_id=?", v.projectId());
        for (var flow : flows.findInProject(v.projectId(), true, PageRequest.of(0, 100))) {
            db.getCollection("flow_documents").deleteMany(and(eq("portfolioId", portfolioId), eq("flowId", flow.getId())));
            db.getCollection("flow_drafts").deleteOne(and(eq("portfolioId", portfolioId), eq("_id", flow.getId())));
            for (var version : versions.findByFlowId(flow.getId(), PageRequest.of(0, 100))) { outbox.deleteById(version.getId()); versions.deleteById(version.getId()); }
            flows.deleteById(flow.getId());
        }
        targets.deleteById(v.targetId()); integrationRepository.deleteById(integrationId); projects.deleteById(v.projectId());
        members.deleteAll(members.findByPortfolioId(portfolioId, PageRequest.of(0, 100)).getContent()); portfolioRepository.deleteById(portfolioId);
        users.deleteById(v.createdBy());
        System.out.println("IBM_WRITE_RECOVERY " + type + " TEMPORARY_RECORDS_CLEANED OK");
    }

    @Test
    @EnabledIfSystemProperty(named = "tabula.skill.write.recover", matches = "true")
    void inspectPreservedOperations() {
        var rows = sql.queryForList("SELECT w.id FROM ibm_skill_writes w JOIN app_users u ON u.id=w.created_by WHERE u.email LIKE 'ibm-write-%@example.invalid'");
        for (var item : rows) {
            var row = ledger.get((String) item.get("id"));
            var v = row.view();
            System.out.println("IBM_WRITE_RECOVERY LOCAL_OPERATION " + v.id() + " " + v.type() + " " + v.state() + " " + v.errorCode());
            var binding = mapper.readValue(row.bindingJson(), com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Binding.class);
            try { System.out.println("IBM_WRITE_RECOVERY REMOTE_SKILL_STATUS " + writeGateway.status(binding).get("status")); }
            catch (IntegrationException ex) { System.out.println("IBM_WRITE_RECOVERY STATUS_ERROR " + ex.code()); }
            var actor = new AccountPrincipal(v.createdBy(), users.findById(v.createdBy()).orElseThrow().getAuthVersion());
            try { System.out.println("IBM_WRITE_RECOVERY RECONCILED_STATE " + skillWrites.reconcile(actor, v.id()).state()); }
            catch (IntegrationException ex) { System.out.println("IBM_WRITE_RECOVERY RECONCILE_ERROR " + ex.code()); }
        }
    }

    @Test void dialogDraftUpdateAndRestore() throws Exception { verifyType(FlowType.DIALOG); }
    @Test void actionDraftUpdateAndRestore() throws Exception { verifyType(FlowType.ACTION); }
    @Test void dialogNodeDeleteAndRecreate() throws Exception { verifyType(FlowType.DIALOG, true); }

    private void verifyType(FlowType type) throws Exception { verifyType(type, false); }

    @SuppressWarnings("unchecked")
    private void verifyType(FlowType type, boolean nodeLifecycle) throws Exception {
        var db = mongo.getDatabase(mongoDatabase);
        String run = UUID.randomUUID().toString();
        var user = new AccountUser("IBM write temporary admin", "ibm-write-" + run + "@example.invalid", "unused", true);
        String portfolioId = null, projectId = null, integrationId = null, targetId = null;
        boolean safeToClean = true;
        String originalRaw = null, originalWorkspaceHash = null;
        VersionView originalVersion = null;
        var actor = new AccountPrincipal(user.getId(), user.getAuthVersion());
        try {
            users.saveAndFlush(user);
            portfolioId = portfolios.create(actor, new PortfolioDtos.Create("IBM write test " + run, null)).id();
            projectId = portfolios.createProject(actor, portfolioId, new PortfolioDtos.Create("Temporary Draft write", null)).id();
            integrationId = integrations.create(actor, portfolioId, new CreateIntegration("IBM test")).id();
            targetId = integrations.createTarget(actor, projectId, new CreateTarget(UUID.fromString(integrationId), settings.targetDefaults(EnvironmentType.DRAFT))).id();
            final String testProject = projectId, testTarget = targetId;
            originalVersion = retryExport(() -> service.importIbm(actor, testProject,
                    new IbmImport(UUID.fromString(testTarget), type, "Original " + type, null), "original-" + run));
            originalRaw = service.export(actor, originalVersion.id());
            var skill = (Map<String, Object>) mapper.readerFor(Object.class)
                    .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS,
                            tools.jackson.databind.DeserializationFeature.USE_BIG_INTEGER_FOR_INTS).readValue(originalRaw);
            var workspace = (Map<String, Object>) skill.get("workspace");
            originalWorkspaceHash = json.checksum(json.encode(workspace));
            var items = (List<Map<String, Object>>) workspace.get(type == FlowType.DIALOG ? "dialog_nodes" : "actions");
            assertThat(items).as("A test skill must contain at least one node/action").isNotEmpty();
            String testNodeId = "tabula_test_" + run.replace("-", "");
            if (nodeLifecycle) {
                var template = items.stream().filter(n -> n.get("parent") == null && "standard".equals(n.get("type"))).findFirst().orElseThrow();
                var temporary = new LinkedHashMap<String, Object>(template);
                temporary.put("dialog_node", testNodeId);
                temporary.put("title", "Tabula deletion recreation test");
                temporary.put("conditions", "false");
                temporary.put("disambiguation_opt_out", true);
                for (String field : List.of("parent", "previous_sibling", "next_step", "context", "actions", "metadata", "user_label", "created", "updated")) temporary.remove(field);
                var roots = items.stream().filter(n -> n.get("parent") == null).toList();
                var predecessors = roots.stream().map(n -> n.get("previous_sibling")).filter(Objects::nonNull).toList();
                var tails = roots.stream().filter(n -> !predecessors.contains(n.get("dialog_node"))).toList();
                assertThat(tails).as("The root sibling chain must have one tail").hasSize(1);
                temporary.put("previous_sibling", tails.getFirst().get("dialog_node"));
                assertThat(items.stream().noneMatch(n -> testNodeId.equals(n.get("dialog_node")))).isTrue();
                items.add(temporary);
            } else {
                items.getFirst().put("title", "Tabula integration test " + run);
            }
            var candidate = service.upload(actor, projectId, new Upload("Temporary edited " + type, type, json.encode(skill)), "candidate-" + run);
            var operation = retryExport(() -> skillWrites.prepare(actor, testProject,
                    new com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Prepare(UUID.fromString(testTarget), UUID.fromString(candidate.id())), "write-" + run));
            assertThat(operation.baselineHash()).isEqualTo(originalWorkspaceHash);
            assertThat(operation.candidateHash()).isNotEqualTo(operation.baselineHash());
            System.out.println("IBM_WRITE_REAL " + type + " BACKUP_READY");
            safeToClean = false; // Preserve backup and operation metadata on any uncertain result.
            try {
                var updated = applyAndWait(actor, operation.id(), type);
                assertThat(updated.state()).as("Update state; code=" + updated.errorCode()).isEqualTo("SUCCEEDED");
                assertThat(skillWrites.apply(actor, operation.id()).state()).isEqualTo("SUCCEEDED");
                System.out.println("IBM_WRITE_REAL " + type + " UPDATE_READBACK_IDEMPOTENCY OK");
                if (nodeLifecycle) {
                    var created = retryExport(() -> exports.export(actor, testProject, testTarget, type.name()));
                    var createdNodes = (List<Map<String, Object>>) ((Map<String, Object>) created.get("workspace")).get("dialog_nodes");
                    assertThat(createdNodes.stream().filter(n -> testNodeId.equals(n.get("dialog_node")))).hasSize(1);
                    System.out.println("IBM_NODE_REAL CREATED_AND_READ_BACK OK");

                    // Apply the original snapshot: only the isolated temporary node is absent.
                    final String baseVersionId = originalVersion.id();
                    var deletion = retryExport(() -> skillWrites.prepare(actor, testProject,
                            new com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Prepare(UUID.fromString(testTarget), UUID.fromString(baseVersionId)), "delete-node-" + run));
                    var deleted = applyAndWait(actor, deletion.id(), type);
                    assertThat(deleted.state()).as("Deletion state; code=" + deleted.errorCode()).isEqualTo("SUCCEEDED");
                    var absent = retryExport(() -> exports.export(actor, testProject, testTarget, type.name()));
                    var absentNodes = (List<Map<String, Object>>) ((Map<String, Object>) absent.get("workspace")).get("dialog_nodes");
                    assertThat(absentNodes.stream().noneMatch(n -> testNodeId.equals(n.get("dialog_node")))).isTrue();
                    assertThat(json.checksum(json.encode(absent.get("workspace")))).isEqualTo(originalWorkspaceHash);
                    System.out.println("IBM_NODE_REAL DELETED_AND_ABSENCE_CONFIRMED OK");

                    // Reuse the same immutable candidate and node identifier, with a new operation key.
                    var recreation = retryExport(() -> skillWrites.prepare(actor, testProject,
                            new com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Prepare(UUID.fromString(testTarget), UUID.fromString(candidate.id())), "recreate-node-" + run));
                    var recreated = applyAndWait(actor, recreation.id(), type);
                    assertThat(recreated.state()).as("Recreation state; code=" + recreated.errorCode()).isEqualTo("SUCCEEDED");
                    var again = retryExport(() -> exports.export(actor, testProject, testTarget, type.name()));
                    var againNodes = (List<Map<String, Object>>) ((Map<String, Object>) again.get("workspace")).get("dialog_nodes");
                    var matches = againNodes.stream().filter(n -> testNodeId.equals(n.get("dialog_node"))).toList();
                    assertThat(matches).hasSize(1);
                    var firstNode = createdNodes.stream().filter(n -> testNodeId.equals(n.get("dialog_node"))).findFirst().orElseThrow();
                    assertThat(json.checksum(json.encode(matches.getFirst()))).isEqualTo(json.checksum(json.encode(firstNode)));
                    System.out.println("IBM_NODE_REAL RECREATED_SAME_ID_AND_CONTENT OK");
                }
            } finally {
                var current = retryExport(() -> exports.export(actor, testProject, testTarget, type.name()));
                String observed = json.checksum(json.encode(current.get("workspace")));
                if (observed.equals(originalWorkspaceHash)) {
                    safeToClean = Set.of("FAILED", "CONFLICT", "READY").contains(skillWrites.find(actor, operation.id()).state());
                } else if ("SUCCEEDED".equals(skillWrites.find(actor, operation.id()).state())) {
                    final String originalId = originalVersion.id();
                    var restore = retryExport(() -> skillWrites.prepare(actor, testProject,
                            new com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Prepare(UUID.fromString(testTarget), UUID.fromString(originalId)), "restore-" + run));
                    var restored = applyAndWait(actor, restore.id(), type);
                    assertThat(restored.state()).as("Restore state; code=" + restored.errorCode()).isEqualTo("SUCCEEDED");
                    var after = retryExport(() -> exports.export(actor, testProject, testTarget, type.name()));
                    assertThat(json.checksum(json.encode(after.get("workspace")))).isEqualTo(originalWorkspaceHash);
                    safeToClean = true;
                    System.out.println("IBM_WRITE_REAL " + type + " ORIGINAL_CONTENT_RESTORED OK");
                }
                if (!safeToClean) System.out.println("IBM_WRITE_REAL " + type + " RECOVERY_REQUIRED_LOCAL_OPERATION " + operation.id());
            }
        } finally {
            if (safeToClean) {
                if (projectId != null) {
                    for (var op : skillWrites.list(actor, projectId, 0, 100).content()) sql.update("DELETE FROM ibm_skill_write_locks WHERE operation_id=?", op.id());
                    db.getCollection("ibm_skill_write_documents").deleteMany(eq("projectId", projectId));
                    sql.update("DELETE FROM ibm_skill_writes WHERE project_id=?", projectId);
                    for (var flow : flows.findInProject(projectId, true, PageRequest.of(0, 100))) {
                        db.getCollection("flow_documents").deleteMany(and(eq("portfolioId", portfolioId), eq("flowId", flow.getId())));
                        db.getCollection("flow_drafts").deleteOne(and(eq("portfolioId", portfolioId), eq("_id", flow.getId())));
                        for (var version : versions.findByFlowId(flow.getId(), PageRequest.of(0, 100))) { outbox.deleteById(version.getId()); versions.deleteById(version.getId()); }
                        flows.deleteById(flow.getId());
                    }
                }
                if (targetId != null) targets.deleteById(targetId);
                if (integrationId != null) integrationRepository.deleteById(integrationId);
                if (projectId != null) projects.deleteById(projectId);
                if (portfolioId != null) { members.deleteAll(members.findByPortfolioId(portfolioId, PageRequest.of(0, 100)).getContent()); portfolioRepository.deleteById(portfolioId); }
                if (users.existsById(user.getId())) users.deleteById(user.getId());
                System.out.println("IBM_WRITE_REAL " + type + " TEMPORARY_RECORDS_CLEANED OK");
            } else {
                System.out.println("IBM_WRITE_REAL " + type + " RECOVERY_PRESERVED_LOCAL_PROJECT " + projectId);
            }
        }
    }
    private com.tabula.tabula_backend.integration.dto.SkillWriteDtos.View applyAndWait(AccountPrincipal actor, String id, FlowType type) throws Exception {
        var current = skillWrites.apply(actor, id);
        for (int attempt = 0; attempt < 60; attempt++) {
            if (Set.of("SUCCEEDED", "FAILED", "CONFLICT").contains(current.state())) return current;
            if (attempt % 5 == 0) System.out.println("IBM_WRITE_REAL " + type + " WAIT " + current.state() + " " + (current.errorCode() == null ? "OK" : current.errorCode()));
            Thread.sleep(2000);
            try { current = "READY".equals(current.state()) ? skillWrites.apply(actor, id) : skillWrites.reconcile(actor, id); }
            catch (IntegrationException ex) {
                if ("IBM_RATE_LIMITED".equals(ex.code())) {
                    System.out.println("IBM_WRITE_REAL " + type + " READ_RATE_LIMIT_BACKOFF"); Thread.sleep(15_000);
                } else if (!"IBM_EXPORT_PENDING".equals(ex.code())) throw ex;
            }
        }
        return current;
    }
    private <T> T retryExport(java.util.concurrent.Callable<T> call) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try { return call.call(); }
            catch (IntegrationException ex) {
                if (!Set.of("IBM_EXPORT_PENDING", "IBM_RATE_LIMITED").contains(ex.code()) || attempt >= 20) throw ex;
                if ("IBM_RATE_LIMITED".equals(ex.code())) System.out.println("IBM_WRITE_REAL READ_RATE_LIMIT_BACKOFF");
                Thread.sleep("IBM_RATE_LIMITED".equals(ex.code()) ? 15_000 : 3000);
            }
        }
    }
}
