package com.tabula.tabula_backend.identity;

import com.jayway.jsonpath.JsonPath;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.identity.service.AccountService;
import com.tabula.tabula_backend.identity.service.IdentityException;
import com.tabula.tabula_backend.identity.model.AccountPrincipal;
import com.tabula.tabula_backend.identity.dto.AccountDtos.AdminUpdate;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
class IdentityIntegrationTests {
    private static final String PASSWORD = "Initial-test-password-123";
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired AccountService accounts;
    @Autowired PasswordEncoder passwords;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        users.deleteAll();
        accounts.bootstrap("Admin", "admin@example.com", PASSWORD);
    }

    @Test
    void loginPersistsSessionAndDoesNotExposeHash() throws Exception {
        MockHttpSession session = login("ADMIN@example.com", PASSWORD);
        mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        var user = users.findByEmail("admin@example.com").orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("$2").isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
    }

    @Test
    void csrfIsRequiredAndRotatedOnLogin() throws Exception {
        MvcResult tokenResponse = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) tokenResponse.getRequest().getSession(false);
        String token = JsonPath.read(tokenResponse.getResponse().getContentAsString(), "$.token");
        String oldSessionId = session.getId();
        mvc.perform(post("/api/v1/auth/login").contentType("application/json")
                .content(loginJson("admin@example.com", PASSWORD))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content(loginJson("admin@example.com", PASSWORD)))
                .andExpect(status().isOk());
        assertThat(session.getId()).isNotEqualTo(oldSessionId);
        mvc.perform(patch("/api/v1/account").session(session).header("X-CSRF-TOKEN", token)
                .contentType("application/json").content("{\"name\":\"Updated\"}"))
                .andExpect(status().isForbidden());
        MvcResult fresh = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        String newToken = JsonPath.read(fresh.getResponse().getContentAsString(), "$.token");
        mvc.perform(patch("/api/v1/account").session(session).header("X-CSRF-TOKEN", newToken)
                .contentType("application/json").content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void invalidCredentialsAreGenericAndAttemptsAreLimited() throws Exception {
        String unknown = "limited@example.com";
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                    .content(loginJson(unknown, "wrong-password")))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content(loginJson(unknown, "wrong-password"))).andExpect(status().isTooManyRequests());
    }

    @Test
    void anonymousAndNonAdminCannotManageUsersOrPromoteThemselves() throws Exception {
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        MockHttpSession admin = login("admin@example.com", PASSWORD);
        create(admin, "user@example.com", false);
        MockHttpSession user = login("user@example.com", PASSWORD);
        mvc.perform(post("/api/v1/admin/users").session(user).with(csrf()).contentType("application/json")
                .content(createJson("another@example.com", false))).andExpect(status().isForbidden());
        mvc.perform(patch("/api/v1/account").session(user).with(csrf()).contentType("application/json")
                .content("{\"name\":\"User\",\"admin\":true}")).andExpect(status().isOk());
        assertThat(users.findByEmail("user@example.com").orElseThrow().isAdmin()).isFalse();
    }

    @Test
    void uniqueEmailAndLastAdminAreProtected() throws Exception {
        MockHttpSession session = login("admin@example.com", PASSWORD);
        mvc.perform(post("/api/v1/admin/users").session(session).with(csrf()).contentType("application/json")
                .content(createJson("ADMIN@example.com", false))).andExpect(status().isConflict());
        String id = users.findByEmail("admin@example.com").orElseThrow().getId();
        for (String update : new String[]{"{\"admin\":false}", "{\"active\":false}"}) {
            mvc.perform(patch("/api/v1/admin/users/" + id).session(session).with(csrf())
                    .contentType("application/json").content(update)).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("LAST_ACTIVE_ADMIN"));
        }
    }

    @Test
    void passwordChangeRequiresCurrentPasswordAndRevokesOtherSessions() throws Exception {
        MockHttpSession first = login("admin@example.com", PASSWORD);
        MockHttpSession second = login("admin@example.com", PASSWORD);
        mvc.perform(put("/api/v1/account/password").session(first).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"incorrect\",\"newPassword\":\"Replacement-password-123\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/v1/account/password").session(first).with(csrf()).contentType("application/json")
                .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"Replacement-password-123\"}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/auth/me").session(second)).andExpect(status().isUnauthorized());
        login("admin@example.com", "Replacement-password-123");
    }

    @Test
    void disablingAccountRevokesSessionAndBlocksLogin() throws Exception {
        MockHttpSession admin = login("admin@example.com", PASSWORD);
        String id = create(admin, "disabled@example.com", false);
        MockHttpSession user = login("disabled@example.com", PASSWORD);
        mvc.perform(patch("/api/v1/admin/users/" + id).session(admin).with(csrf()).contentType("application/json")
                .content("{\"active\":false}")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/auth/me").session(user)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content(loginJson("disabled@example.com", PASSWORD))).andExpect(status().isUnauthorized());
    }

    @Test
    void demotionRevokesExistingAdminSession() throws Exception {
        MockHttpSession admin = login("admin@example.com", PASSWORD);
        String id = create(admin, "other-admin@example.com", true);
        MockHttpSession other = login("other-admin@example.com", PASSWORD);
        mvc.perform(patch("/api/v1/admin/users/" + id).session(admin).with(csrf()).contentType("application/json")
                .content("{\"admin\":false}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/users").session(other).with(csrf()).contentType("application/json")
                .content(createJson("blocked@example.com", false))).andExpect(status().isUnauthorized());
    }

    @Test
    void profileEmailChangeNormalizesAndLogsOut() throws Exception {
        MockHttpSession first = login("admin@example.com", PASSWORD);
        MockHttpSession second = login("admin@example.com", PASSWORD);
        mvc.perform(patch("/api/v1/account").session(first).with(csrf()).contentType("application/json")
                .content("{\"email\":\"NEW@example.com\",\"name\":\"New Name\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value("new@example.com"));
        mvc.perform(get("/api/v1/auth/me").session(second)).andExpect(status().isUnauthorized());
        login("new@example.com", PASSWORD);
    }

    @Test
    void validationDoesNotLeakPasswordsAndHandlesUtf8Limit() throws Exception {
        MockHttpSession admin = login("admin@example.com", PASSWORD);
        String secret = "á".repeat(40);
        mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf()).contentType("application/json")
                .content("{\"name\":\"User\",\"email\":\"utf8@example.com\",\"password\":\"" + secret + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().string(not(containsString(secret))));
        mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf()).contentType("application/json")
                .content("{\"name\":\"\",\"email\":\"bad\",\"password\":\"secret\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(content().string(not(containsString("secret"))));
    }

    @Test
    void logoutInvalidatesSessionAndBootstrapDoesNotOverwriteAccounts() throws Exception {
        accounts.bootstrap("Changed", "admin@example.com", "Other-password-123");
        assertThat(users.findByEmail("admin@example.com").orElseThrow().getName()).isEqualTo("Admin");
        MockHttpSession session = login("admin@example.com", PASSWORD);
        mvc.perform(post("/api/v1/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentDemotionsKeepOneActiveAdministrator() throws Exception {
        MockHttpSession admin = login("admin@example.com", PASSWORD);
        create(admin, "concurrent@example.com", true);
        var first = users.findByEmail("admin@example.com").orElseThrow();
        var second = users.findByEmail("concurrent@example.com").orElseThrow();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<Future<String>>();
            for (var user : java.util.List.of(first, second)) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) { throw new AssertionError("Start timeout"); }
                    try {
                        accounts.updateByAdmin(new AccountPrincipal(user.getId(), user.getAuthVersion()),
                                user.getId(), new AdminUpdate(null, null, false, null));
                        return "UPDATED";
                    } catch (IdentityException ex) { return ex.code(); }
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(java.util.List.of(results.get(0).get(10, TimeUnit.SECONDS), results.get(1).get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("UPDATED", "LAST_ACTIVE_ADMIN");
        }
        assertThat(users.countByAdminTrueAndActiveTrue()).isEqualTo(1);
    }

    private MockHttpSession login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/auth/login").with(csrf()).contentType("application/json")
                .content(loginJson(email, password))).andExpect(status().isOk()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Test
    void passwordMinimumIsEightForCreationChangeAndBootstrap() throws Exception {
        MockHttpSession admin = login("admin@example.com", PASSWORD);
        for (String candidate : new String[]{"Seven12", "Eight123"}) {
            mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf()).contentType("application/json")
                            .content("{\"name\":\"Boundary User\",\"email\":\"boundary@example.com\",\"password\":\"" + candidate + "\",\"admin\":false}"))
                    .andExpect(status().is(candidate.length() == 8 ? 201 : 400));
        }
        login("boundary@example.com", "Eight123");
        for (String candidate : new String[]{"Seven12", "Eight123"}) {
            mvc.perform(put("/api/v1/account/password").session(admin).with(csrf()).contentType("application/json")
                            .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"" + candidate + "\"}"))
                    .andExpect(status().is(candidate.length() == 8 ? 204 : 400));
        }
        login("admin@example.com", "Eight123");

        // This class always uses its isolated H2 database, never the local MySQL configuration.
        users.deleteAll();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        accounts.bootstrap("Admin", "bootstrap@example.com", "Seven12"))
                .isInstanceOf(IdentityException.class);
        assertThat(users.count()).isZero();
        accounts.bootstrap("Admin", "bootstrap@example.com", "Eight123");
        login("bootstrap@example.com", "Eight123");
    }

    private String create(MockHttpSession admin, String email, boolean isAdmin) throws Exception {
        MvcResult result = mvc.perform(post("/api/v1/admin/users").session(admin).with(csrf())
                .contentType("application/json").content(createJson(email, isAdmin)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordHash").doesNotExist()).andReturn();
        return JsonPath.read(result.getResponse().getContentAsString(), "$.id");
    }

    private static String loginJson(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private static String createJson(String email, boolean admin) {
        return "{\"name\":\"User\",\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"admin\":" + admin + "}";
    }
}
