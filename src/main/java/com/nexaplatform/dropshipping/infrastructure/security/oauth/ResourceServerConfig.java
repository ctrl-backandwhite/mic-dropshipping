package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class ResourceServerConfig {

    /**
     * Chain for partner-facing API: stateless JWT bearer tokens, scope-based authorization.
     * Incluye un filtro de revocación que rechaza tokens emitidos antes de un cambio
     * de plan o de la eliminación de la credencial.
     *
     * <p>SEGURIDAD: el decoder VALIDA el {@code iss} (issuer) contra el issuer de ESTE entorno
     * ({@code nexadrop.oauth.issuer}). Así un token emitido en DES no se acepta en PRE (ni al revés):
     * los tokens quedan ligados a su entorno y no se pueden cruzar. Antes usaba el decoder por defecto,
     * que solo comprobaba la firma y permitía el cruce entre entornos.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain partnerApiFilterChain(HttpSecurity http, JwtRevocationFilter revocationFilter,
            JWKSource<SecurityContext> jwkSource, @Value("${nexadrop.oauth.issuer}") String issuer)
            throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("scope");
        authoritiesConverter.setAuthorityPrefix("SCOPE_");
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        NimbusJwtDecoder decoder = (NimbusJwtDecoder) OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));

        http.securityMatcher("/api/v1/partner/**")// NOSONAR java:S4502 — API de partners con token Bearer: sin cookies de sesión, CSRF no aplica.
                .csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults()) // NOSONAR java:S4502 — API de partners con Bearer
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(revocationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(reg -> reg.requestMatchers("/api/v1/partner/catalog/**")
                        .hasAuthority("SCOPE_catalog.read").requestMatchers("/api/v1/partner/orders/**")
                        .hasAuthority("SCOPE_orders.write").requestMatchers("/api/v1/partner/shop/**")
                        .hasAuthority("SCOPE_shop.sync").anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)));

        return http.build();
    }
}
