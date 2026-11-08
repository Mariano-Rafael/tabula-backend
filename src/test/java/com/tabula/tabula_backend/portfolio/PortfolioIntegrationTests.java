package com.tabula.tabula_backend.portfolio;

import com.jayway.jsonpath.JsonPath;
import com.tabula.tabula_backend.identity.model.*;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.portfolio.dto.PortfolioDtos.*;
import com.tabula.tabula_backend.portfolio.model.*;
import com.tabula.tabula_backend.portfolio.repository.*;
import com.tabula.tabula_backend.portfolio.service.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class PortfolioIntegrationTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PortfolioRepository portfolios;
    @Autowired PortfolioMemberRepository members;
    @Autowired ProjectRepository projects;
    @Autowired PortfolioService service;
    private MockMvc mvc;
    private AccountUser owner;
    private AccountUser outsider;
    private AccountUser admin;

    @BeforeEach
    void setup() {
        cleanup();
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        owner = user(false);
        outsider = user(false);
        admin = user(true);
    }

    @AfterEach
    void cleanup() {
        // This class runs only against the isolated test database.
        projects.deleteAll();
        members.deleteAll();
        portfolios.deleteAll();
        users.deleteAll();
    }

    @Test
    void creatorBecomesOwnerAndOnlyAccessiblePortfoliosAreListed() throws Exception {
        var created = mvc.perform(post("/api/v1/portfolios").with(as(owner)).with(csrf())
                .contentType("application/json").content("{\"name\":\"  Portfolio A  \"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Portfolio A")).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");
        assertThat(members.findByPortfolioIdAndUserId(id, owner.getId()).orElseThrow().getRole()).isEqualTo(PortfolioRole.OWNER);
        service.create(principal(outsider), new Create("Portfolio B", null));
        mvc.perform(get("/api/v1/portfolios").with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].id").value(id));
        mvc.perform(get("/api/v1/portfolios").with(as(admin))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void nonMemberCannotReadOrChangeAnotherPortfolioOrItsProjects() throws Exception {
        String id = portfolio();
        String project = service.createProject(principal(owner), id, new Create("Project", null)).id();
        mvc.perform(get("/api/v1/portfolios/" + id).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/portfolios/" + id + "/members").with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/projects/" + project).with(as(outsider))).andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/projects/" + project).with(as(outsider)).with(csrf())
                .contentType("application/json").content("{\"name\":\"Changed\"}")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/portfolios/" + id + "/members").with(as(outsider)).with(csrf())
                .contentType("application/json").content(memberJson(outsider, PortfolioRole.OWNER))).andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @EnumSource(PortfolioRole.class)
    void roleMatrixControlsProjectsAndPortfolioManagement(PortfolioRole role) throws Exception {
        String id = portfolio();
        service.addMember(principal(owner), id, new AddMember(UUID.fromString(outsider.getId()), role));
        mvc.perform(get("/api/v1/portfolios/" + id).with(as(outsider))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/portfolios/" + id + "/projects").with(as(outsider)).with(csrf())
                .contentType("application/json").content("{\"name\":\"Project\"}"))
                .andExpect(status().is(role.canEditProjects() ? 201 : 403));
        mvc.perform(patch("/api/v1/portfolios/" + id).with(as(outsider)).with(csrf())
                .contentType("application/json").content("{\"description\":\"Updated\"}"))
                .andExpect(status().is(role == PortfolioRole.OWNER ? 200 : 403));
        mvc.perform(post("/api/v1/portfolios/" + id + "/members").with(as(outsider)).with(csrf())
                .contentType("application/json").content(memberJson(admin, PortfolioRole.VIEWER)))
                .andExpect(status().is(role == PortfolioRole.OWNER ? 201 : 403));
    }

    @Test
    void lastOwnerCannotBeRemovedOrDemotedAndGlobalAdminCanRecoverAccess() throws Exception {
        String id = portfolio();
        mvc.perform(delete("/api/v1/portfolios/" + id + "/members/" + owner.getId()).with(as(owner)).with(csrf()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("LAST_PORTFOLIO_OWNER"));
        mvc.perform(patch("/api/v1/portfolios/" + id + "/members/" + owner.getId()).with(as(admin)).with(csrf())
                .contentType("application/json").content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/portfolios/" + id + "/members").with(as(admin)).with(csrf())
                .contentType("application/json").content(memberJson(outsider, PortfolioRole.OWNER))).andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/portfolios/" + id + "/members/" + owner.getId()).with(as(owner)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/portfolios/" + id).with(as(owner))).andExpect(status().isNotFound());
    }

    @Test
    void membershipChangesTakeEffectWithoutRelogin() throws Exception {
        String id = portfolio();
        service.addMember(principal(owner), id, new AddMember(UUID.fromString(outsider.getId()), PortfolioRole.EDITOR));
        service.changeRole(principal(owner), id, outsider.getId(), new ChangeRole(PortfolioRole.VIEWER));
        mvc.perform(post("/api/v1/portfolios/" + id + "/projects").with(as(outsider)).with(csrf())
                .contentType("application/json").content("{\"name\":\"Denied\"}")).andExpect(status().isForbidden());
        service.removeMember(principal(owner), id, outsider.getId());
        mvc.perform(get("/api/v1/portfolios/" + id).with(as(outsider))).andExpect(status().isNotFound());
    }

    @Test
    void archivedResourcesRemainReadableButCannotBeChanged() throws Exception {
        String id = portfolio();
        String projectId = service.createProject(principal(owner), id, new Create("Project", null)).id();
        mvc.perform(delete("/api/v1/projects/" + projectId).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/portfolios/" + id + "/projects").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/portfolios/" + id + "/projects?includeArchived=true").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(patch("/api/v1/projects/" + projectId).with(as(owner)).with(csrf())
                .contentType("application/json").content("{\"name\":\"Blocked\"}")).andExpect(status().isConflict());
        mvc.perform(delete("/api/v1/portfolios/" + id).with(as(owner)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/portfolios/" + id).with(as(owner))).andExpect(status().isOk())
                .andExpect(jsonPath("$.archived").value(true));
        mvc.perform(get("/api/v1/portfolios").with(as(owner))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(get("/api/v1/portfolios?includeArchived=true").with(as(owner))).andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(post("/api/v1/portfolios/" + id + "/projects").with(as(owner)).with(csrf())
                .contentType("application/json").content("{\"name\":\"Blocked\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/v1/portfolios/" + id + "/members").with(as(owner)).with(csrf())
                .contentType("application/json").content(memberJson(outsider, PortfolioRole.OWNER))).andExpect(status().isConflict());
        assertThat(projects.existsById(projectId)).isTrue();
    }

    @Test
    void validationAndMembershipConstraintsReturnStableErrors() throws Exception {
        String id = portfolio();
        mvc.perform(get("/api/v1/portfolios")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/portfolios").with(as(owner)).contentType("application/json")
                .content("{\"name\":\"No csrf\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/portfolios").with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"   \"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/portfolios?size=101").with(as(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/portfolios?page=-1").with(as(owner))).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/portfolios/not-a-uuid").with(as(owner))).andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/portfolios/" + id + "/members").with(as(owner)).with(csrf())
                .contentType("application/json").content(memberJson(owner, PortfolioRole.OWNER))).andExpect(status().isConflict());
        outsider.updateAccess(false, false);
        users.saveAndFlush(outsider);
        mvc.perform(post("/api/v1/portfolios/" + id + "/members").with(as(owner)).with(csrf())
                .contentType("application/json").content(memberJson(outsider, PortfolioRole.VIEWER))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/v1/portfolios/" + id + "/members").with(as(owner)).with(csrf())
                .contentType("application/json").content("{\"userId\":\"" + owner.getId() + "\",\"role\":\"INVALID\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void paginationIsStableAndProjectCannotMoveBetweenPortfolios() throws Exception {
        String id = portfolio();
        String other = service.create(principal(outsider), new Create("Other", null)).id();
        String projectId = service.createProject(principal(owner), id, new Create("Project", null)).id();
        service.createProject(principal(owner), id, new Create("Project 2", null));
        mvc.perform(get("/api/v1/portfolios/" + id + "/projects?size=1&page=1").with(as(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(patch("/api/v1/projects/" + projectId).with(as(owner)).with(csrf()).contentType("application/json")
                .content("{\"name\":\"Updated\",\"portfolioId\":\"" + other + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.portfolioId").value(id));
    }

    @Test
    void concurrentOwnerDemotionsPreserveOneOwner() throws Exception {
        String id = portfolio();
        service.addMember(principal(owner), id, new AddMember(UUID.fromString(outsider.getId()), PortfolioRole.OWNER));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<Future<String>>();
            for (var user : List.of(owner, outsider)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Start timeout"); }
                    try {
                        service.changeRole(principal(user), id, user.getId(), new ChangeRole(PortfolioRole.VIEWER));
                        return "UPDATED";
                    } catch (PortfolioException ex) { return ex.code(); }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("UPDATED", "LAST_PORTFOLIO_OWNER");
        }
        assertThat(members.countByPortfolioIdAndRole(id, PortfolioRole.OWNER)).isEqualTo(1);
    }

    private String portfolio() { return service.create(principal(owner), new Create("Portfolio", null)).id(); }
    private AccountUser user(boolean globalAdmin) {
        return users.saveAndFlush(new AccountUser("Test user", UUID.randomUUID() + "@example.invalid", "unused-in-portfolio-tests", globalAdmin));
    }
    private static AccountPrincipal principal(AccountUser u) { return new AccountPrincipal(u.getId(), u.getAuthVersion()); }
    private static RequestPostProcessor as(AccountUser u) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal(u), null,
                List.of(new SimpleGrantedAuthority(u.isAdmin() ? "ROLE_ADMIN" : "ROLE_USER"))));
    }
    private static String memberJson(AccountUser user, PortfolioRole role) {
        return "{\"userId\":\"" + user.getId() + "\",\"role\":\"" + role + "\"}";
    }
}
