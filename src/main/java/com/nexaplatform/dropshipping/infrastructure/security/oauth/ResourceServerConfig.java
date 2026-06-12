package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
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
     */
    @Bean
    @Order(1)
    public SecurityFilterChain partnerApiFilterChain(HttpSecurity http, JwtRevocationFilter revocationFilter)
            throws Exception {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("scope");
        authoritiesConverter.setAuthorityPrefix("SCOPE_");
        converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        http.securityMatcher("/api/v1/partner/**").csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(revocationFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(reg -> reg.requestMatchers("/api/v1/partner/catalog/**")
                        .hasAuthority("SCOPE_catalog.read").requestMatchers("/api/v1/partner/orders/**")
                        .hasAuthority("SCOPE_orders.write").requestMatchers("/api/v1/partner/shop/**")
                        .hasAuthority("SCOPE_shop.sync").anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)));

        return http.build();
    }
}
