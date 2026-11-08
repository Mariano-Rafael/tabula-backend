package com.tabula.tabula_backend.flow;

import com.mongodb.client.MongoClient;
import com.tabula.tabula_backend.flow.dto.FlowDtos.*;
import com.tabula.tabula_backend.flow.repository.*;
import com.tabula.tabula_backend.flow.service.*;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import static com.mongodb.client.model.Filters.*;
import static org.assertj.core.api.Assertions.*;

/** Real local persistence, no IBM calls. Removes only fixtures created by this run. */
@EnabledIfSystemProperty(named = "tabula.layout.local.verify", matches = "true")
@SpringBootTest(properties = {"spring.config.location=file:src/main/resources/application.properties",
        "app.bootstrap-admin.enabled=false", "logging.level.root=ERROR", "spring.main.banner-mode=off"})
class LocalFlowLayoutTests {
    @Autowired MongoClient mongo;
    @Value("${spring.mongodb.database}") String database;
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired PortfolioService portfolios;
    @Autowired FlowService flows;
    @Autowired FlowLayoutService layouts;
    @Autowired FlowLayoutStore layoutStore;
    @Autowired FlowJsonCodec codec;
    @Autowired FlowRepository flowRepository;
    @Autowired FlowVersionRepository versions;
    @Autowired FlowWriteRepository outbox;
    private static final String RAW = "{\"type\":\"dialog\",\"workspace\":{\"dialog_nodes\":[]},\"unknown\":{\"$a.b\":true}}";
    private static final String LAYOUT = "{\"schemaVersion\":1,\"nodes\":[{\"id\":\"group\",\"position\":{\"x\":0,\"y\":0}},{\"id\":\"a\",\"position\":{\"x\":12,\"y\":34},\"parentId\":\"group\"}],\"viewport\":{\"x\":0,\"y\":0,\"zoom\":1}}";
    @Test void mongoPersistsGroupsAndAtomicallyRejectsConcurrentWrites() throws Exception {
        var account = users.saveAndFlush(new AccountUser("Layout temporary admin", "layout-"+ UUID.randomUUID()+"@example.invalid", "unused", true));
        var actor = new AccountPrincipal(account.getId(), account.getAuthVersion());
        String portfolioId = null, projectId = null, flowId = null;
        try {
            portfolioId = portfolios.create(actor, new PortfolioDtos.Create("Layout local test", null)).id();
            projectId = portfolios.createProject(actor, portfolioId, new PortfolioDtos.Create("Temporary layout project", null)).id();
            var original = flows.upload(actor, projectId, new Upload("Layout fixture", FlowType.DIALOG, RAW), UUID.randomUUID().toString());
            flowId = original.flowId();
            flows.startDraft(actor, flowId, original.id());
            layouts.save(actor, flowId, null, "*", LAYOUT);
            assertThat(layoutStore.find(portfolioId, flowId)).isPresent();
            assertThat(layouts.find(actor, flowId).nodes().get(1).parentId()).isEqualTo("group");
            final String testFlow = flowId, scope = portfolioId;
            var start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                List<Future<Boolean>> futures = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    int x = i + 50;
                    futures.add(executor.submit(() -> {
                        if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("Start timeout");
                        // Exercise Mongo CAS directly so SQL portfolio locks cannot mask a race.
                        String raw = LAYOUT.replace("\"x\":12", "\"x\":" + x);
                        try { layoutStore.save(scope, testFlow, 1L, raw, codec.checksum(raw), actor.id()); return true; }
                        catch (FlowException ex) { if (!"LAYOUT_REVISION_MISMATCH".equals(ex.code())) throw ex; return false; }
                    }));
                }
                start.countDown();
                int successes = 0;
                for (var f : futures) if (f.get(15, TimeUnit.SECONDS)) successes++;
                assertThat(successes).isEqualTo(1);
            }
            assertThat(layoutStore.find(portfolioId, flowId).orElseThrow().revision()).isEqualTo(2);
            layouts.save(actor, flowId, "\"layout-2\"", null, LAYOUT);
            assertThat(layouts.find(actor, flowId).revision()).isEqualTo(3);
            assertThat(flows.export(actor, original.id())).isEqualTo(RAW);
            assertThat(flows.draft(actor, flowId).rawJson()).isEqualTo(RAW);
            assertThat(flows.draft(actor, flowId).revision()).isEqualTo(1);
            System.out.println("LAYOUT_LOCAL PERSIST_GROUPS_CONCURRENT_CAS_IBM_JSON_UNCHANGED OK");
        } finally {
            if (flowId != null) {
                var db = mongo.getDatabase(database);
                db.getCollection("flow_layouts").deleteOne(and(eq("_id", flowId), eq("portfolioId", portfolioId)));
                db.getCollection("flow_documents").deleteMany(and(eq("flowId", flowId), eq("portfolioId", portfolioId)));
                db.getCollection("flow_drafts").deleteOne(and(eq("_id", flowId), eq("portfolioId", portfolioId)));
                for (var v : versions.findByFlowId(flowId, PageRequest.of(0, 100))) { outbox.deleteById(v.getId()); versions.deleteById(v.getId()); }
                flowRepository.deleteById(flowId);
            }
            if (projectId != null) projects.deleteById(projectId);
            if (portfolioId != null) { members.deleteAll(members.findByPortfolioId(portfolioId, PageRequest.of(0, 100)).getContent()); portfolioRepository.deleteById(portfolioId); }
            users.deleteById(account.getId());
        }
        System.out.println("LAYOUT_LOCAL TEMPORARY_RECORDS_CLEANED OK");
    }
}
