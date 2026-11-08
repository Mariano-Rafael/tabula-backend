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

/** Opt-in: real local databases plus IBM GET exports. Deletes only this run's fixtures. */
@EnabledIfSystemProperty(named = "tabula.flow.import.verify", matches = "true")
@SpringBootTest(properties = {
        "spring.config.location=file:src/main/resources/application.properties", "app.bootstrap-admin.enabled=false",
        "logging.level.root=ERROR", "spring.main.banner-mode=off",
        "spring.mongodb.connect-timeout=5s", "spring.mongodb.socket-timeout=10s"
})
class LocalFlowImportTests {
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

    @Test
    void mongoAndMysqlPersistRealDialogAndActionExports() throws Exception {
        var db = mongo.getDatabase(mongoDatabase);
        assertThat(((Number) db.runCommand(new Document("ping", 1)).get("ok")).doubleValue()).isEqualTo(1.0);
        System.out.println("FLOW_LOCAL MONGODB_PING OK");
        String run = UUID.randomUUID().toString();
        var user = new AccountUser("Flow import temporary admin", "flow-import-" + run + "@example.invalid", "unused", true);
        String portfolioId = null, projectId = null, integrationId = null, targetId = null;
        try {
            users.saveAndFlush(user);
            var actor = new AccountPrincipal(user.getId(), user.getAuthVersion());
            portfolioId = portfolios.create(actor, new PortfolioDtos.Create("Flow import " + run, null)).id();
            projectId = portfolios.createProject(actor, portfolioId, new PortfolioDtos.Create("Temporary flow project", null)).id();
            integrationId = integrations.create(actor, portfolioId, new CreateIntegration("IBM")).id();
            targetId = integrations.createTarget(actor, projectId, new CreateTarget(UUID.fromString(integrationId), settings.targetDefaults(EnvironmentType.DRAFT))).id();
            for (var type : FlowType.values()) {
                var request = new IbmImport(UUID.fromString(targetId), type, "Temporary imported " + type, null);
                String key = UUID.randomUUID().toString();
                VersionView result = null;
                for (int attempt = 0; attempt < 8; attempt++) {
                    try { result = service.importIbm(actor, projectId, request, key); break; }
                    catch (IntegrationException ex) {
                        if (!"IBM_EXPORT_PENDING".equals(ex.code()) || attempt == 7) { throw ex; }
                        Thread.sleep(1500);
                    }
                }
                assertThat(result).isNotNull();
                assertThat(result.state()).isEqualTo("READY");
                String raw = service.export(actor, result.id());
                assertThat(json.validate(raw, type)).isEqualTo(result.checksum());
                assertThat(service.importIbm(actor, projectId, request, key).id()).isEqualTo(result.id());
                var draft = service.startDraft(actor, result.flowId(), result.id());
                String edited = raw + "\n";
                var updated = service.updateDraft(actor, result.flowId(), "\"" + draft.revision() + "\"", edited);
                assertThat(updated.revision()).isEqualTo(2);
                var second = service.snapshotDraft(actor, result.flowId(), "\"2\"", UUID.randomUUID().toString());
                assertThat(second.number()).isEqualTo(2);
                assertThat(service.export(actor, result.id())).isEqualTo(raw);
                System.out.println("FLOW_LOCAL " + type.name() + " IMPORT_SNAPSHOT_DRAFT OK");
                var writeRequest = new com.tabula.tabula_backend.integration.dto.SkillWriteDtos.Prepare(UUID.fromString(targetId), UUID.fromString(second.id()));
                String writeKey = UUID.randomUUID().toString();
                com.tabula.tabula_backend.integration.dto.SkillWriteDtos.View operation = null;
                for (int attempt = 0; attempt < 8; attempt++) {
                    try { operation = skillWrites.prepare(actor, projectId, writeRequest, writeKey); break; }
                    catch (IntegrationException ex) {
                        if (!"IBM_EXPORT_PENDING".equals(ex.code()) || attempt == 7) throw ex;
                        Thread.sleep(1500);
                    }
                }
                assertThat(operation).isNotNull();
                assertThat(operation.state()).isEqualTo("READY");
                assertThat(operation.baselineHash()).isEqualTo(operation.candidateHash());
                assertThat(skillWrites.prepare(actor, projectId, writeRequest, writeKey).id()).isEqualTo(operation.id());
                assertThat(json.checksum(skillWrites.backup(actor, operation.id()).baselineJson())).isEqualTo(operation.baselineHash());
                System.out.println("FLOW_LOCAL " + type.name() + " WRITE_PREPARATION_BACKUP_ONLY OK");
            }
        } finally {
            if (projectId != null) {
                db.getCollection("ibm_skill_write_documents").deleteMany(eq("projectId", projectId));
                sql.update("DELETE FROM ibm_skill_writes WHERE project_id=?", projectId);
                for (var flow : flows.findInProject(projectId, true, PageRequest.of(0, 100))) {
                    db.getCollection("flow_documents").deleteMany(and(eq("portfolioId", portfolioId), eq("flowId", flow.getId())));
                    db.getCollection("flow_drafts").deleteOne(and(eq("portfolioId", portfolioId), eq("_id", flow.getId())));
                    for (var version : versions.findByFlowId(flow.getId(), PageRequest.of(0, 100))) { outbox.deleteById(version.getId()); versions.deleteById(version.getId()); }
                    flows.deleteById(flow.getId());
                }
            }
            if (targetId != null) { targets.deleteById(targetId); }
            if (integrationId != null) { integrationRepository.deleteById(integrationId); }
            if (projectId != null) { projects.deleteById(projectId); }
            if (portfolioId != null) {
                members.deleteAll(members.findByPortfolioId(portfolioId, PageRequest.of(0, 100)).getContent()); portfolioRepository.deleteById(portfolioId);
            }
            users.deleteById(user.getId());
        }
        assertThat(users.existsById(user.getId())).isFalse();
        System.out.println("FLOW_LOCAL TEMPORARY_RECORDS_CLEANED OK");
    }
}
