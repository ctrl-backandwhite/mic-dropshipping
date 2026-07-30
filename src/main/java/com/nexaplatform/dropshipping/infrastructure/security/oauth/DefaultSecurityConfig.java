package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.infrastructure.security.jwt.UserTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class DefaultSecurityConfig {

    @Value("${nexadrop.storefront.base-url}")
    private String frontBaseUrl;

    @Bean
    public GoogleOAuth2SuccessHandler googleOAuth2SuccessHandler(UserUseCase userUseCase,
            UserTokenService userTokenService) {
        return new GoogleOAuth2SuccessHandler(userUseCase, userTokenService, frontBaseUrl);
    }

    @Bean
    @Order(3)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http,
            GoogleOAuth2SuccessHandler googleOAuth2SuccessHandler,
            GithubOAuth2UserService githubOAuth2UserService) throws Exception {
        http.cors(Customizer.withDefaults())
                // Aquí CSRF está ACTIVO (esta cadena sí tiene formulario y sesión); solo se exceptúan
                // rutas concretas. La cookie va sin HttpOnly a propósito: el patrón de doble envío exige
                // que el navegador lea el token por JavaScript para reenviarlo en la cabecera. Es el
                // token CSRF, no la sesión: la de sesión sí es HttpOnly.
                // NOSONAR java:S4502 java:S3330 — CSRF habilitado; cookie legible por diseño del patrón.
                .csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()) // NOSONAR
                        // CSRF off for: OAuth2 token, OAuth callbacks, actuator, public storefront API,
                        // inbound webhooks (signed HMAC), Stripe / PayPal payment callbacks.
                        .ignoringRequestMatchers("/oauth2/token", "/login/oauth2/code/**", "/actuator/**", // NOSONAR java:S4502 — rutas sin sesión: OAuth2, callbacks y webhooks con firma propia
                                "/api/v1/rate-limits/**", "/api/v1/invoices/**", "/api/v1/integrations/**", "/api/webhooks/**"))
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .frameOptions(fo -> fo.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/", "/login", "/login/**", "/register", "/activate", "/activate/**",
                                "/password-reset", "/password-reset/**", "/error", "/.well-known/**", "/oauth2/**",
                                "/userinfo", "/actuator/health", "/actuator/info",
                                // Public storefront catalog + signed inbound webhooks + payment callbacks.
                                "/api/v1/rate-limits/**", "/api/v1/invoices/**", "/api/v1/integrations/**", "/api/webhooks/**", "/css/**",
                                "/js/**", "/img/**", "/assets/**", "/favicon.ico")
                        .permitAll()
                        // Swagger UI + OpenAPI JSON quedan tras login y solo accesibles a staff.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/v3/api-docs.yaml/**",
                                "/swagger-ui.html", "/swagger-ui/**", "/swagger-resources/**", "/webjars/**")
                        .hasAnyAuthority("ROLE_ADMIN", "ROLE_OPERATOR").anyRequest().authenticated())
                .formLogin(form -> form.loginPage("/login").permitAll())
                .oauth2Login(oauth -> oauth.loginPage("/login")
                        // GitHub (no-OIDC) usa nuestro user service para resolver el email verificado;
                        // Google (OIDC) sigue con el OidcUserService por defecto.
                        .userInfoEndpoint(userInfo -> userInfo.userService(githubOAuth2UserService))
                        .successHandler(googleOAuth2SuccessHandler)
                        .failureHandler((req, res, ex) -> res.sendRedirect(frontBaseUrl + "/login?error=google"))
                        .permitAll());
        return http.build();
    }
}
