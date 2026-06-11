package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration
public class BffSecurityConfig {

    /**
     * Internal admin/storefront BFF chain — session-based with CSRF cookie.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain bffFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/admin/**", "/api/storefront/**", "/api/me/**", "/api/auth/**", "/api/webhooks/**")
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .ignoringRequestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/activate",
                                "/api/auth/password-reset/**",
                                "/api/webhooks/**",
                                "/api/storefront/catalog/shipping/quote",
                                "/api/storefront/catalog/products/*/variants/match",
                                "/api/storefront/catalog/products/import-url",
                                "/api/storefront/catalog/products/search-by-image",
                                "/api/storefront/shipping/calculator",
                                "/api/storefront/shipping/carbon-footprint"))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/activate",
                                "/api/auth/password-reset/**",
                                "/api/webhooks/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/storefront/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/storefront/catalog/shipping/quote").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/storefront/catalog/products/*/variants/match").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/storefront/catalog/products/import-url").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/storefront/catalog/products/search-by-image").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/storefront/shipping/calculator").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/storefront/shipping/carbon-footprint").permitAll()
                        .requestMatchers("/api/admin/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "OPERATOR")
                        // /api/me is the auth-bootstrap probe — it must succeed even when
                        // unauthenticated (the controller returns null), otherwise the SPA
                        // sees a noisy 403 on every cold load before login.
                        .requestMatchers(HttpMethod.GET, "/api/me").permitAll()
                        .requestMatchers("/api/me/**").authenticated()
                        .anyRequest().authenticated())
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(204))
                        .invalidateHttpSession(true)
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN"));
        return http.build();
    }
}
