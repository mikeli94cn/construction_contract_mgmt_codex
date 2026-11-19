package com.construction.contracts.identity.infrastructure;

import com.construction.contracts.identity.domain.Account;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.http.HttpMethod;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

@Configuration
public class IdentitySecurity {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    AuthenticationManager authenticationManager(AccountRepository accounts, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(email -> accounts.findByEmail(Account.normalize(email))
            .filter(Account::active)
            .map(account -> User.withUsername(Long.toString(account.id()))
                .password(account.passwordHash()).authorities("AUTHENTICATED").build())
            .orElseThrow(() -> new UsernameNotFoundException("Account unavailable")));
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    HttpSessionSecurityContextRepository securityContexts() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    HttpSessionCsrfTokenRepository csrfTokens() {
        return new HttpSessionCsrfTokenRepository();
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http, HttpSessionSecurityContextRepository contexts,
                                 HttpSessionCsrfTokenRepository tokens) throws Exception {
        return http
            .securityContext(context -> context.securityContextRepository(contexts))
            .csrf(csrf -> csrf.csrfTokenRepository(tokens))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/activation").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) ->
                    problem(response, 401, "authentication_required", "Sign in to continue"))
                .accessDeniedHandler((request, response, failure) ->
                    problem(response, 403, "access_denied", "Permission or CSRF validation failed")))
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .build();
    }

    static void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.getWriter().printf("""
            {"type":"about:blank","status":%d,"code":"%s","detail":"%s"}""", status, code, detail);
    }
}
