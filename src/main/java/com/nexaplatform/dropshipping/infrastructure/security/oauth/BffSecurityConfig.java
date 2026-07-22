package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class BffSecurityConfig {

    /**
     * Decoder DEDICADO a la cadena de usuario (distinto del de partner). Sobre la validación
     * estándar (firma RSA + exp/nbf + issuer) añade dos controles que blindan el login:
     * <ul>
     *   <li><b>typ=access</b>: rechaza usar un refresh token (14 días) o un token de partner
     *       como Bearer de acceso (token-type / cross-token confusion).</li>
     *   <li><b>no revocado</b>: consulta {@link JwtRevocationService} para que el logout
     *       invalide el access token de inmediato (no esperar a su expiración).</li>
     * </ul>
     */
    private NimbusJwtDecoder userJwtDecoder(JWKSource<SecurityContext> jwkSource, JwtRevocationService revocation,
            String issuer) {
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        OAuth2TokenValidator<Jwt> accessOnly = jwt -> "access".equals(jwt.getClaimAsString("typ"))
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Access token required", null));
        OAuth2TokenValidator<Jwt> notRevoked = jwt -> {
            long iat = jwt.getIssuedAt() != null ? jwt.getIssuedAt().getEpochSecond() : 0L;
            return revocation.isStillValid(jwt.getSubject(), iat) ? OAuth2TokenValidatorResult.success()
                    : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Token revoked", null));
        };
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), accessOnly,
                        notRevoked));
        return decoder;
    }

    /**
     * Cadena interna admin/storefront para el SPA — auth por <b>token Bearer (JWT)</b>, stateless.
     *
     * <p>Migrada desde sesión+cookies: el SPA estático vive en otro dominio (Railway) y manda
     * {@code Authorization: Bearer <token>}. Al no haber cookies no hay CSRF, y el resource server
     * valida el JWT (firmado por el JWKSource RSA compartido). Las autoridades salen del claim
     * {@code authorities} del token (p.ej. {@code ROLE_ADMIN}), sin prefijo extra.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain bffFilterChain(HttpSecurity http, JWKSource<SecurityContext> jwkSource,
            JwtRevocationService revocationService, @Value("${nexadrop.oauth.issuer}") String issuer) throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("authorities");
        authorities.setAuthorityPrefix("");
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        NimbusJwtDecoder decoder = userJwtDecoder(jwkSource, revocationService, issuer);

        http.securityMatcher("/api/admin/**", "/api/me/**", "/api/auth/**", "/api/webhooks/**",
                // Endpoints públicos del SPA (antes agrupados bajo /api/storefront/**, ahora sin ese segmento).
                "/api/catalog/**", "/api/billing/**", "/api/contact", "/api/contact/**", "/api/newsletter/**",
                "/api/affiliate/**", "/api/search", "/api/search/**", "/api/shipping/**", "/api/currency/**",
                "/api/languages", "/api/languages/**", "/api/warehouses", "/api/warehouses/**", "/api/academy/**",
                "/api/mentors", "/api/mentors/**", "/api/pod/**", "/api/campaigns/**")
                .cors(Customizer.withDefaults()).csrf(csrf -> csrf.disable())
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .frameOptions(fo -> fo.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        // API JSON: nada debe cargarse/embeber → CSP mínima.
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(reg -> reg.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Endpoints públicos de auth: aún no hay token.
                        .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/activate",
                                "/api/auth/refresh", "/api/auth/password-reset/**", "/api/webhooks/**")
                        .permitAll()
                        // El estimado de margen/ganancia es SOLO para ADMIN (ni USER ni OPERATOR/soporte).
                        // Debe ir ANTES del permitAll general de GET del catálogo público.
                        .requestMatchers(HttpMethod.GET, "/api/catalog/products/*/margin-estimate")
                        .hasRole("ADMIN")
                        // GET públicos de navegación (antes GET /api/storefront/**), enumerados por base.
                        .requestMatchers(HttpMethod.GET, "/api/catalog/**", "/api/billing/**", "/api/contact",
                                "/api/contact/**", "/api/newsletter/**", "/api/affiliate/**", "/api/search",
                                "/api/search/**", "/api/shipping/**", "/api/currency/**", "/api/languages",
                                "/api/languages/**", "/api/warehouses", "/api/warehouses/**", "/api/academy/**",
                                "/api/mentors", "/api/mentors/**", "/api/pod/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/catalog/shipping/quote").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/catalog/cart-quote").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/catalog/products/*/variants/match")
                        .permitAll().requestMatchers(HttpMethod.POST, "/api/catalog/products/import-url")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/catalog/products/search-by-image")
                        .permitAll().requestMatchers(HttpMethod.POST, "/api/shipping/calculator").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/shipping/carbon-footprint").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/affiliate/track").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/newsletter/subscribe").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/newsletter/unsubscribe").permitAll()
                        // Baja/alta de correos de campaña por enlace de un clic (token HMAC, sin login).
                        .requestMatchers(HttpMethod.GET, "/api/campaigns/unsubscribe", "/api/campaigns/resubscribe")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/contact").permitAll()
                        // OPERATOR (soporte) SOLO puede: procesar órdenes y ver sus propias ganancias/historial.
                        // Todo lo demás del admin (pricing/márgenes, dashboard/estadísticas, catálogo, usuarios,
                        // monedas, impuestos, partners, billing, afiliados…) es EXCLUSIVO de ADMIN.
                        .requestMatchers("/api/admin/orders/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/admin/operator/**").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        // /api/me is the auth-bootstrap probe — it must succeed even when
                        // unauthenticated (the controller returns null), otherwise the SPA
                        // sees a noisy 401 on every cold load before login.
                        .requestMatchers(HttpMethod.GET, "/api/me").permitAll().requestMatchers("/api/me/**")
                        .authenticated().anyRequest().authenticated())
                .oauth2ResourceServer(
                        oauth -> oauth.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)));
        return http.build();
    }
}
