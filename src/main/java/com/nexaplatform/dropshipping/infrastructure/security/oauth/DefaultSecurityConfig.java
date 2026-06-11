package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;

@Configuration
public class DefaultSecurityConfig {

    @Bean
    @Order(3)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        // CSRF off for: OAuth2 token, OAuth callbacks, actuator, public storefront API,
                        // inbound webhooks (signed HMAC), Stripe / PayPal payment callbacks.
                        .ignoringRequestMatchers(
                                "/oauth2/token", "/login/oauth2/code/**", "/actuator/**",
                                "/api/v1/storefront/**",
                                "/api/v1/integrations/**",
                                "/api/webhooks/**"))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(
                                "/", "/login", "/login/**", "/register", "/activate", "/activate/**",
                                "/password-reset", "/password-reset/**", "/error",
                                "/.well-known/**", "/oauth2/**", "/userinfo",
                                "/actuator/health", "/actuator/info",
                                // Public storefront catalog + signed inbound webhooks + payment callbacks.
                                "/api/v1/storefront/**",
                                "/api/v1/integrations/**",
                                "/api/webhooks/**",
                                "/css/**", "/js/**", "/img/**", "/assets/**", "/favicon.ico").permitAll()
                        // Swagger UI + OpenAPI JSON quedan tras login y solo accesibles a staff.
                        .requestMatchers(
                                "/v3/api-docs", "/v3/api-docs/**",
                                "/v3/api-docs.yaml", "/v3/api-docs.yaml/**",
                                "/swagger-ui.html", "/swagger-ui/**",
                                "/swagger-resources/**", "/webjars/**")
                                .hasAnyAuthority("ROLE_ADMIN", "ROLE_OPERATOR")
                        .anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll());
        return http.build();
    }
}
