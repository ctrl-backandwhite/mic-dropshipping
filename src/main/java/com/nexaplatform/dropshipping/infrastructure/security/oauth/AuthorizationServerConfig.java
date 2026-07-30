package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.infrastructure.security.jwk.JwkKeyService;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

@Configuration
public class AuthorizationServerConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) {
        OAuth2AuthorizationServerConfigurer authServerConfigurer = new OAuth2AuthorizationServerConfigurer();

        http.securityMatcher(authServerConfigurer.getEndpointsMatcher())
                .with(authServerConfigurer, c -> c.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(reg -> reg.anyRequest().authenticated())
                // NOSONAR java:S4502 — endpoints OAuth2 (token, JWKS): los llama el cliente con credenciales propias, no el navegador con cookies.
                .csrf(csrf -> csrf.ignoringRequestMatchers(authServerConfigurer.getEndpointsMatcher())) // NOSONAR java:S4502 — endpoints OAuth2 llamados por el cliente con credenciales propias
                .exceptionHandling(
                        ex -> ex.defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login"),
                                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

        return http.build();
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings(@Value("${nexadrop.oauth.issuer}") String issuer) {
        return AuthorizationServerSettings.builder().issuer(issuer).build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(JwkKeyService service) {
        return service.asJwkSource();
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    /**
     * Encoder para firmar los JWT de USUARIO (access/refresh) que emite el login por token.
     * Usa el mismo JWKSource RSA rotado por {@code JwkKeyService}, de modo que el mismo
     * {@link JwtDecoder} valida tanto los tokens de partner como los de usuario.
     */
    /**
     * Encoder que firma los JWT (tokens de USUARIO y del flujo OAuth2 client_credentials). Usa una
     * {@link JWKSource} que expone SOLO la clave activa: así el {@code JwtGenerator} del Authorization
     * Server —que no fija el {@code kid} en la cabecera— tiene una única clave candidata y no falla con
     * "multiple keys for the signing algorithm [null]" cuando hay claves rotadas en el {@code JWKSource}
     * de validación. Los tokens de usuario, que además fijan el {@code kid} activo, siguen funcionando.
     */
    @Bean
    public JwtEncoder jwtEncoder(JwkKeyService jwkKeyService) {
        return new NimbusJwtEncoder(jwkKeyService.signingJwkSource());
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(
            AuthenticationConfiguration cfg)
 {
        return cfg.getAuthenticationManager();
    }
}
