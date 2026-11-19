package com.construction.contracts.identity.api;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.construction.contracts.identity.application.IdentityService;
import com.construction.contracts.identity.application.MembershipStore.Membership;
import com.construction.contracts.identity.domain.Account;
import com.construction.contracts.identity.domain.OrganizationRole;
import com.construction.contracts.identity.infrastructure.AccountRepository;
import com.construction.contracts.identity.infrastructure.IdentitySecurity;
import jakarta.servlet.Filter;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.json.JsonMapper;

class AuthMvcTest {
    @Test
    void csrfSessionRotationAndLogoutUseTheSecurityFilterChain() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(WebConfiguration.class);
            context.refresh();
            var mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
            String password = UUID.randomUUID().toString();
            String email = "admin@example.test";
            Account account = mock(Account.class);
            when(account.id()).thenReturn(1L);
            when(account.active()).thenReturn(true);
            when(account.passwordHash()).thenReturn(context.getBean(PasswordEncoder.class).encode(password));
            when(context.getBean(AccountRepository.class).findByEmail(email)).thenReturn(Optional.of(account));
            var organization = new Membership(7, "Site", 1, email, "Admin", "ACTIVE", true, 0,
                Set.of(OrganizationRole.ORG_ADMIN));
            when(context.getBean(IdentityService.class).profile(1))
                .thenReturn(new IdentityService.Profile(1, email, "Admin", List.of(organization)));

            var session = new MockHttpSession();
            String originalSessionId = session.getId();
            String login = JsonMapper.builder().build().writeValueAsString(java.util.Map.of(
                "email", email, "password", password));
            mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/auth/login").session(session).contentType("application/json").content(login))
                .andExpect(status().isForbidden());

            var csrf = csrf(mvc, session);
            mvc.perform(post("/api/v1/auth/login").session(session).contentType("application/json").content(login)
                    .header(csrf.headerName(), csrf.token()))
                .andExpect(status().isNoContent());
            assertNotEquals(originalSessionId, session.getId());
            mvc.perform(get("/api/v1/auth/me").session(session))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeOrganizationId").value("7"));

            mvc.perform(post("/api/v1/auth/logout").session(session).header(csrf.headerName(), csrf.token()))
                .andExpect(status().isForbidden());
            var refreshed = csrf(mvc, session);
            mvc.perform(post("/api/v1/auth/logout").session(session).header(refreshed.headerName(), refreshed.token()))
                .andExpect(status().isNoContent());
            mvc.perform(get("/api/v1/auth/me")).andExpect(status().isUnauthorized());
        }
    }

    private AuthController.Csrf csrf(MockMvc mvc, MockHttpSession session) throws Exception {
        var response = mvc.perform(get("/api/v1/auth/csrf").session(session)).andExpect(status().isOk())
            .andReturn().getResponse();
        return JsonMapper.builder().build().readValue(response.getContentAsString(), AuthController.Csrf.class);
    }

    @Configuration
    @EnableWebMvc
    @EnableWebSecurity
    @Import({IdentitySecurity.class, AuthController.class, IdentityErrors.class})
    static class WebConfiguration {
        @Bean
        AccountRepository accounts() {
            return mock(AccountRepository.class);
        }

        @Bean
        IdentityService identityService() {
            return mock(IdentityService.class);
        }
    }
}
