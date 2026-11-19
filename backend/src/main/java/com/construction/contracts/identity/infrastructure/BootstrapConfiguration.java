package com.construction.contracts.identity.infrastructure;

import com.construction.contracts.identity.application.IdentityService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true")
class BootstrapConfiguration {
    @Bean
    ApplicationRunner organizationBootstrap(Environment env, IdentityService identity) {
        return args -> identity.bootstrap(env.getRequiredProperty("app.bootstrap.code"),
            env.getRequiredProperty("app.bootstrap.name"), env.getRequiredProperty("app.bootstrap.email"),
            env.getRequiredProperty("app.bootstrap.password"));
    }
}
