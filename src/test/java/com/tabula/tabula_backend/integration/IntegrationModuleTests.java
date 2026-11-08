package com.tabula.tabula_backend.integration;

import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.integration.client.*;
import com.tabula.tabula_backend.integration.dto.IntegrationDtos.*;
import com.tabula.tabula_backend.integration.repository.*;
import com.tabula.tabula_backend.integration.service.*;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos;
import com.tabula.tabula_backend.portfolio.model.PortfolioRole;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.PortfolioService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"ibm.watson.api-key=test-secret", "ibm.watson.service-url=https://api.us-south.assistant.watson.cloud.ibm.com/instances/test"})
class IntegrationModuleTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolioRepository;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired IbmIntegrationRepository integrationRepository;
    @Autowired IbmTargetRepository targetRepository;
    @Autowired PortfolioService portfolios;
    @Autowired IntegrationService service;
    @Autowired IbmTokenProvider tokens;
    @Autowired ObjectMapper json;
    @MockitoBean IbmHttpTransport http;
    private MockMvc mvc;
    private AccountUser admin, owner, stranger;
    private String portfolioId, projectId, integrationId;

    @BeforeEach
    void setup() {
        cleanup();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        admin = user(true); owner = user(false); stranger = user(false);
        portfolioId = portfolios.create(principal(owner), new PortfolioDtos.Create("Portfolio", null)).id();
        projectId = portfolios.createProject(principal(owner), portfolioId, new PortfolioDtos.Create("Project", null)).id();
        integrationId = service.create(principal(admin), portfolioId, new CreateIntegration("IBM")).id();
        tokens.invalidate();
        when(http.authenticate()).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of("access_token", "fake-token", "expiration", Instant.now().plusSeconds(3600).getEpochSecond());
        });
        when(http.get(anyString(), anyString(), anyString(), anyBoolean())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return Map.of();
        });
    }
    @AfterEach
    void cleanup() {
        targetRepository.deleteAll(); integrationRepository.deleteAll(); projects.deleteAll();
        members.deleteAll(); portfolioRepository.deleteAll(); users.deleteAll();
    }

    @Test
    void onlyGlobalAdminCanDelegateConfiguredCredentialAndNoSecretsAreExposed() throws Exception {
        String other = portfolios.create(principal(owner), new PortfolioDtos.Create("Other", null)).id();
        mvc.perform(post("/api/v1/portfolios/" + other + "/integrations").with(as(owner)).with(csrf())
                .contentType("application/json").content("{\"name\":\"IBM\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("GLOBAL_ADMIN_REQUIRED"));
        mvc.perform(post("/api/v1/portfolios/" + other + "/integrations").with(as(admin)).with(csrf())
                .contentType("application/json").content("{\"name\":\"IBM\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.credentialSource").value("PROPERTIES"))
                .andExpect(content().string(not(containsString("test-secret"))));
        mvc.perform(post("/api/v1/portfolios/" + other + "/integrations").with(as(admin)).with(csrf())
                .contentType("application/json").content("{\"name\":\"IBM\"}")).andExpect(status().isConflict());
        mvc.perform(get("/api/v1/integrations/" + integrationId).with(as(stranger))).andExpect(status().isNotFound());
    }

    @Test
    void ownerCanCreateReplaceAndDeactivateTargetButOtherReadersCannot() throws Exception {
        var created = mvc.perform(post("/api/v1/projects/" + projectId + "/targets").with(as(owner)).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(new CreateTarget(UUID.fromString(integrationId), configuration()))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.active").value(true)).andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        portfolios.addMember(principal(owner), portfolioId, new PortfolioDtos.AddMember(UUID.fromString(stranger.getId()), PortfolioRole.EDITOR));
        mvc.perform(get("/api/v1/targets/" + id).with(as(stranger))).andExpect(status().isOk());
        mvc.perform(put("/api/v1/targets/" + id).with(as(stranger)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(configuration()))).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/targets/" + id).with(as(owner)).with(csrf()).contentType("application/json")
                .content(json.writeValueAsString(configuration()))).andExpect(status().isOk());
        mvc.perform(delete("/api/v1/targets/" + id).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/targets/" + id + "/connection-tests").with(as(owner)).with(csrf())).andExpect(status().isConflict());
        verifyNoInteractions(http);
    }

    @Test
    void probesRunOutsideTransactionsAndReportPartialFailuresWithoutRawBodies() throws Exception {
        String id = createTarget();
        when(http.get(contains("/skills/action"), anyString(), anyString(), anyBoolean()))
                .thenThrow(new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_PERMISSION_DENIED", "ignored-sensitive-body"));
        mvc.perform(post("/api/v1/targets/" + id + "/connection-tests").with(as(owner)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.successful").value(false))
                .andExpect(jsonPath("$.checks[?(@.resource == 'ACTION_SKILL')].code").value(hasItem("IBM_PERMISSION_DENIED")))
                .andExpect(content().string(not(containsString("ignored-sensitive-body"))))
                .andExpect(content().string(not(containsString("fake-token"))));
        verify(http).get("/v1/workspaces/workspace", "2021-11-27", "fake-token", false);
        verify(http).get("/v2/assistants/assistant/environments/draft", "2024-08-25", "fake-token", false);
    }

    @Test
    void integrationProbeChecksBothApisAndDisabledIntegrationStopsRemoteCalls() throws Exception {
        mvc.perform(post("/api/v1/integrations/" + integrationId + "/connection-tests").with(as(owner)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.successful").value(true));
        verify(http).get("/v1/workspaces", "2021-11-27", "fake-token", true);
        verify(http).get("/v2/assistants", "2024-08-25", "fake-token", true);
        clearInvocations(http);
        service.update(principal(owner), integrationId, new UpdateIntegration(null, false));
        mvc.perform(post("/api/v1/integrations/" + integrationId + "/connection-tests").with(as(owner)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INTEGRATION_DISABLED"));
        verifyNoInteractions(http);
    }

    @Test
    void crossPortfolioBindingsAndInvalidIdentifiersOrDatesAreRejected() throws Exception {
        String other = portfolios.create(principal(stranger), new PortfolioDtos.Create("Other", null)).id();
        String otherIntegration = service.create(principal(admin), other, new CreateIntegration("Other IBM")).id();
        mvc.perform(post("/api/v1/projects/" + projectId + "/targets").with(as(owner)).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(new CreateTarget(UUID.fromString(otherIntegration), configuration()))))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/projects/" + projectId + "/targets").with(as(admin)).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(new CreateTarget(UUID.fromString(otherIntegration), configuration()))))
                .andExpect(status().isUnprocessableEntity());
        for (String value : List.of("../escape", "bad?id")) {
            var bad = new TargetConfig("Target", EnvironmentType.DRAFT, "assistant", value, null, null, null, "2021-11-27", "2024-08-25");
            mvc.perform(post("/api/v1/projects/" + projectId + "/targets").with(as(owner)).with(csrf())
                    .contentType("application/json").content(json.writeValueAsString(new CreateTarget(UUID.fromString(integrationId), bad))))
                    .andExpect(status().isBadRequest());
        }
        var badDate = new TargetConfig("Target", EnvironmentType.DRAFT, "assistant", "draft", null, null, null, "2021-02-30", "2024-08-25");
        mvc.perform(post("/api/v1/projects/" + projectId + "/targets").with(as(owner)).with(csrf())
                .contentType("application/json").content(json.writeValueAsString(new CreateTarget(UUID.fromString(integrationId), badDate))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void capabilitiesDoNotClaimWriteOrRuntimeSupportAndUnknownPermissionsStayUnverified() throws Exception {
        String id = createTarget();
        mvc.perform(get("/api/v1/targets/" + id + "/capabilities").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.operation == 'READ_ACTION_SKILL')].permissionStatus").value(hasItem("NOT_VERIFIED")))
                .andExpect(jsonPath("$[?(@.operation == 'WRITE_FLOW')].implemented").value(hasItem(false)));
        mvc.perform(get("/api/v1/projects/" + projectId + "/targets?size=1").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/v1/targets/" + id).with(as(stranger))).andExpect(status().isNotFound());
        verifyNoInteractions(http);
    }

    @Test
    void csrfAuthorizationAndArchivalAreCheckedBeforeSendingRequests() throws Exception {
        String id = createTarget();
        mvc.perform(post("/api/v1/targets/" + id + "/connection-tests").with(as(owner))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/targets/" + id + "/connection-tests").with(as(stranger)).with(csrf())).andExpect(status().isNotFound());
        portfolios.archiveProject(principal(owner), projectId);
        mvc.perform(post("/api/v1/targets/" + id + "/connection-tests").with(as(owner)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESOURCE_ARCHIVED"));
        verifyNoInteractions(http);
    }

    @Test
    void unsupportedDiscoveryIsReportedWithoutDisablingResourceAccess() throws Exception {
        when(http.get(eq("/v2/assistants"), anyString(), anyString(), eq(true)))
                .thenThrow(new IntegrationException(HttpStatus.BAD_GATEWAY, "IBM_OPERATION_NOT_SUPPORTED", "ignored"));
        mvc.perform(post("/api/v1/integrations/" + integrationId + "/connection-tests").with(as(owner)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.successful").value(false))
                .andExpect(jsonPath("$.checks[?(@.resource == 'V2_ASSISTANTS')].status").value(hasItem("UNSUPPORTED")));
        String id = createTarget();
        mvc.perform(post("/api/v1/targets/" + id + "/connection-tests").with(as(owner)).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.successful").value(true));
    }

    private String createTarget() { return service.createTarget(principal(owner), projectId, new CreateTarget(UUID.fromString(integrationId), configuration())).id(); }
    private static TargetConfig configuration() {
        return new TargetConfig("Target", EnvironmentType.DRAFT, "assistant", "draft", "workspace", "dialog", "action", "2021-11-27", "2024-08-25");
    }
    private AccountUser user(boolean admin) { return users.saveAndFlush(new AccountUser("Test", UUID.randomUUID() + "@example.invalid", "unused", admin)); }
    private static AccountPrincipal principal(AccountUser user) { return new AccountPrincipal(user.getId(), user.getAuthVersion()); }
    private static RequestPostProcessor as(AccountUser user) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal(user), null,
                List.of(new SimpleGrantedAuthority(user.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER"))));
    }
}
