package com.tabula.tabula_backend.identity.config;

import com.tabula.tabula_backend.identity.service.AccountService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration
public class BootstrapAdminConfig {
    @Bean
    @ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
    ApplicationRunner bootstrapAdmin(AccountService accounts,
                                     @Value("${app.bootstrap-admin.name:Administrador}") String name,
                                     @Value("${app.bootstrap-admin.email:}") String email,
                                     @Value("${app.bootstrap-admin.password:}") String password) {
        return args -> accounts.bootstrap(name, email, password);
    }
}
