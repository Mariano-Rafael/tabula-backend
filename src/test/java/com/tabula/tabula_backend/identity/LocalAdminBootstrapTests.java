package com.tabula.tabula_backend.identity;

import com.jayway.jsonpath.JsonPath;
import com.tabula.tabula_backend.identity.repository.UserRepository;
import com.tabula.tabula_backend.identity.service.AccountService;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Administrative opt-in operation: creates the first PERMANENT account through the startup runner. */
@EnabledIfSystemProperty(named = "tabula.admin.bootstrap.verify", matches = "true")
@SpringBootTest(properties = {
        "spring.config.location=file:src/main/resources/application.properties",
        "spring.autoconfigure.exclude=org.springframework.boot.mongodb.autoconfigure.MongoAutoConfiguration",
        "logging.level.root=ERROR",
        "spring.main.banner-mode=off"
})
class LocalAdminBootstrapTests {
    @Autowired WebApplicationContext context;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    @Autowired DataSource dataSource;
    @Value("${app.bootstrap-admin.email}") String email;
    @Value("${app.bootstrap-admin.password}") String password;

    @Test
    void configuredPermanentAdminExistsAndCanLogin() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        var account = users.findByEmail(AccountService.normalizeEmail(email));
        assertThat(account.isPresent()).as("Configured administrator exists; bootstrap never overwrites existing accounts").isTrue();
        assertThat(account.orElseThrow().isAdmin()).as("Administrator privilege").isTrue();
        assertThat(account.orElseThrow().isActive()).as("Active account").isTrue();
        assertThat(passwords.matches(password, account.orElseThrow().getPasswordHash()))
                .as("Configured password matches the stored hash").isTrue();

        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var initial = mvc.perform(get("/api/v1/auth/csrf")).andExpect(status().isOk()).andReturn();
        var session = (MockHttpSession) initial.getRequest().getSession(false);
        String csrf = JsonPath.read(initial.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/v1/auth/login").session(session).header("X-CSRF-TOKEN", csrf)
                        .contentType("application/json").content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
        mvc.perform(get("/api/v1/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true)).andExpect(jsonPath("$.admin").value(true));
        var fresh = mvc.perform(get("/api/v1/auth/csrf").session(session)).andReturn();
        csrf = JsonPath.read(fresh.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/v1/auth/logout").session(session).header("X-CSRF-TOKEN", csrf))
                .andExpect(status().isNoContent());
        // Intentionally retain the administrator. No delete or password reset is performed.
    }
}
