package com.tabula.tabula_backend.identity;

import com.jayway.jsonpath.JsonPath;
import com.tabula.tabula_backend.identity.model.AccountUser;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Explicitly opted-in smoke test. Never runs in the regular isolated test suite. */
@EnabledIfSystemProperty(named = "tabula.mysql.verify", matches = "true")
@SpringBootTest(properties = {
        "spring.config.location=file:src/main/resources/application.properties",
        "app.bootstrap-admin.enabled=false",
        "spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "logging.level.root=ERROR",
        "spring.main.banner-mode=off"
})
class LocalMysqlIdentityTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired DataSource dataSource;

    @Test
    void realMysqlPersistsAccountsAndAuthenticatesWithCsrf() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
            try (var statement = connection.prepareStatement(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = true");
                 var result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isGreaterThanOrEqualTo(1);
            }
        }

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String runId = UUID.randomUUID().toString();
        String adminEmail = "mysql-smoke-admin-" + runId + "@example.invalid";
        String userEmail = "mysql-smoke-user-" + runId + "@example.invalid";
        String password = UUID.randomUUID() + "-Temporary!";
        // Insert only our isolated fixture, without changing bootstrap or existing accounts.
        try {
            users.saveAndFlush(new AccountUser("MySQL smoke temporary admin", adminEmail,
                    passwords.encode(password), true));
            MvcResult initial = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
            MockHttpSession session = (MockHttpSession) initial.getRequest().getSession(false);
            String csrf = JsonPath.read(initial.getResponse().getContentAsString(), "$.token");
            mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN", csrf)
                    .contentType("application/json").content("{\"email\":\"" + adminEmail
                            + "\",\"password\":\"" + password + "\"}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true));

            MvcResult renewed = mvc.perform(get("/api/v1/auth/csrf").session(session))
                    .andExpect(status().isOk()).andReturn();
            csrf = JsonPath.read(renewed.getResponse().getContentAsString(), "$.token");
            mvc.perform(post("/api/v1/admin/users").session(session).header("X-CSRF-TOKEN", csrf)
                    .contentType("application/json").content("{\"name\":\"MySQL smoke temporary user\","
                            + "\"email\":\"" + userEmail + "\",\"password\":\"" + password + "\",\"admin\":false}"))
                    .andExpect(status().isCreated()).andExpect(jsonPath("$.passwordHash").doesNotExist());
            var stored = users.findByEmail(userEmail).orElseThrow();
            assertThat(stored.isAdmin()).isFalse();
            assertThat(passwords.matches(password, stored.getPasswordHash())).isTrue();

            mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk());
            mvc.perform(post("/api/v1/auth/logout").session(session).header("X-CSRF-TOKEN", csrf))
                    .andExpect(status().isNoContent());
            assertThat(session.isInvalid()).isTrue();
        } finally {
            // No deleteAll: keep every pre-existing account untouched.
            users.findByEmail(userEmail).ifPresent(user -> users.deleteById(user.getId()));
            users.findByEmail(adminEmail).ifPresent(user -> users.deleteById(user.getId()));
        }
        assertThat(users.findByEmail(userEmail)).isEmpty();
        assertThat(users.findByEmail(adminEmail)).isEmpty();
    }
}
