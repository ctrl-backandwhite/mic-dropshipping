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
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
public class BffSecurityConfig {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String API_CONTACT = "/api/contact";
    private static final String ADMIN = "ADMIN";

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
     *
     * <h4>Por qué SPRING_CSRF_PROTECTION_DISABLED es falso positivo en esta cadena</h4>
     *
     * <p>El argumento no es «es una API, CSRF no aplica» —eso sería falso si algún endpoint aceptara la
     * cookie—, sino que aquí la cookie <b>no puede autenticar nada</b>, y eso lo garantiza el propio
     * framework: con {@link SessionCreationPolicy#STATELESS}, {@code SessionManagementConfigurer} sustituye
     * el {@code SecurityContextRepository} de la cadena por {@code RequestAttributeSecurityContextRepository}
     * y deja {@code NullSecurityContextRepository} en la gestión de sesión. La {@code HttpSession} nunca se
     * lee, así que un {@code JSESSIONID} que el navegador enviara por su cuenta se ignora por completo: la
     * ÚNICA credencial admitida es la cabecera {@code Authorization: Bearer}, que ningún formulario ni
     * etiqueta {@code <img>} de un tercero puede hacer viajar. CSRF explota el envío AUTOMÁTICO de
     * credenciales por el navegador; una cabecera no se manda sola.
     *
     * <p>Consecuencia práctica: TODA la superficie con efectos de esta cadena (POST/PUT/PATCH/DELETE de
     * {@code /api/admin/**}, {@code /api/me/**}, checkout, wallet…) exige Bearer o responde 401, como
     * comprueba {@code BffEndpointAuthorizationIT}. La única cadena con sesión —y por tanto con CSRF
     * ACTIVO— es {@code DefaultSecurityConfig}.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain bffFilterChain(HttpSecurity http, JWKSource<SecurityContext> jwkSource,
            JwtRevocationService revocationService, UserTokenRevocationFilter userTokenRevocationFilter,
            @Value("${nexadrop.oauth.issuer}") String issuer) {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("authorities");
        authorities.setAuthorityPrefix("");
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        NimbusJwtDecoder decoder = userJwtDecoder(jwkSource, revocationService, issuer);

        http.securityMatcher("/api/admin/**", "/api/me/**", "/api/auth/**", "/api/webhooks/**",
                // Endpoints públicos del SPA (antes agrupados bajo /api/storefront/**, ahora sin ese segmento).
                "/api/catalog/**", "/api/billing/**", API_CONTACT, "/api/contact/**", "/api/newsletter/**",
                "/api/affiliate/**", "/api/search", "/api/search/**", "/api/shipping/**", "/api/currency/**",
                "/api/languages", "/api/languages/**", "/api/warehouses", "/api/warehouses/**", "/api/academy/**",
                "/api/mentors", "/api/mentors/**", "/api/pod/**", "/api/campaigns/**", "/api/geo",
                // El asistente conversacional. Tiene que estar AQUÍ además de en las reglas de abajo:
                // esto decide qué cadena atiende la petición, y aquello qué se le exige. Sin esta línea
                // la regla de abajo no llega a evaluarse nunca y la petición cae en la cadena por
                // defecto, que no lee el token Bearer — un 403 con credenciales perfectamente válidas.
                "/api/chat",
                // Cumplimiento del Reglamento (UE) 2023/988. Tiene que estar AQUÍ además de en las reglas
                // de autorización de abajo: lo que no entra en este securityMatcher lo atiende la cadena
                // del servidor de autorización, que responde 302 hacia /login — o sea, la ruta parece
                // protegida pero en realidad ni siquiera llega a evaluarse como API.
                "/api/compliance", "/api/compliance/**",
                // Textos legales: cualquiera debe poder leerlos ANTES de registrarse.
                "/api/legal", "/api/legal/**",
                "/api/captcha/**",
                // Vuelta del pago a la aplicación. La abre el NAVEGADOR al salir de la pasarela, sin
                // testigo ninguno: solo redirige al esquema del teléfono y no toca dinero ni datos.
                "/api/payments/app-return")
                .cors(Customizer.withDefaults())
                // NOSONAR java:S4502 — Falso positivo verificado: con STATELESS (abajo) la sesión no se lee
                // nunca, así que ninguna ruta con efectos se autentica por cookie. Detalle en el javadoc.
                .csrf(csrf -> csrf.disable()) // NOSONAR java:S4502 — API stateless con Bearer, sin cookie de sesión
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
                                "/api/auth/activate/resend", "/api/auth/refresh", "/api/auth/password-reset/**",
                                "/api/webhooks/**")
                        .permitAll()
                        // Reto CAPTCHA (proof-of-work): el navegador lo pide antes de enviar un formulario público.
                        .requestMatchers(HttpMethod.GET, "/api/captcha/challenge").permitAll()
                        // La vuelta del pago llega desde el navegador de la pasarela, sin sesión.
                        .requestMatchers(HttpMethod.GET, "/api/payments/app-return").permitAll()
                        // El estimado de margen/ganancia es SOLO para ADMIN (ni USER ni OPERATOR/soporte).
                        // Debe ir ANTES del permitAll general de GET del catálogo público.
                        .requestMatchers(HttpMethod.GET, "/api/catalog/products/*/margin-estimate")
                        .hasRole(ADMIN)
                        // El catálogo se navega con cuenta. El muro estaba SOLO en el frontend
                        // (ProtectedRoute), que oculta la vista pero no cierra la API: sin ninguna
                        // credencial se podían sacar 100 productos por llamada —con precio, ventas
                        // mensuales y trend score—, o sea el catálogo entero en ~45 peticiones. Un
                        // scraper no usa el navegador.
                        //
                        // Se cierran los dos endpoints que permiten ENUMERAR, y solo esos:
                        // la ficha individual sigue abierta (hay que conocer el slug) y también
                        // /api/catalog/home/sections, que la portada pública necesita y devuelve un
                        // puñado de productos por sección, no el catálogo.
                        .requestMatchers(HttpMethod.GET, "/api/catalog/products").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/search", "/api/search/**").authenticated()
                        // El asistente conversacional busca en el catálogo por dentro, así que dejarlo
                        // abierto abriría por la puerta de atrás justo lo que las dos líneas de arriba
                        // cierran: volcar el catálogo sin cuenta, preguntando. Además cada mensaje cuesta
                        // dinero en el proveedor del modelo, y un endpoint anónimo de pago es una factura
                        // ajena esperando a que alguien la encuentre.
                        .requestMatchers(HttpMethod.POST, "/api/chat").authenticated()
                        // El simulador de la guía de bienvenida. Es un POST porque manda las cantidades que
                        // el visitante va poniendo, pero lo ve justo quien AÚN NO TIENE CUENTA: cerrarlo
                        // dejaría la guía sin números para su único público. No es una calculadora abierta:
                        // el controlador solo acepta los tres productos que la propia guía propone y como
                        // mucho seis unidades de cada uno, así que no sirve para tarifar un catálogo.
                        .requestMatchers(HttpMethod.POST, "/api/catalog/welcome/simulate").permitAll()
                        // GET públicos de navegación (antes GET /api/storefront/**), enumerados por base.
                        .requestMatchers(HttpMethod.GET, "/api/catalog/**", "/api/billing/**", API_CONTACT,
                                "/api/contact/**", "/api/newsletter/**", "/api/affiliate/**", "/api/search",
                                "/api/search/**", "/api/shipping/**", "/api/currency/**", "/api/languages",
                                "/api/languages/**", "/api/warehouses", "/api/warehouses/**", "/api/academy/**",
                                "/api/mentors", "/api/mentors/**", "/api/pod/**", "/api/geo",
                                // Operador económico de la UE (art. 16.3 del Reglamento (UE) 2023/988): la
                                // norma obliga a que el comprador pueda verlo, así que no puede quedar
                                // detrás del muro de cuenta. No expone nada que no deba ser público.
                                "/api/compliance", "/api/compliance/**",
                                // Términos, privacidad, cookies, aviso legal y desistimiento. Exigir cuenta
                                // para leer las condiciones que uno va a aceptar no tendría sentido.
                                "/api/legal", "/api/legal/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/catalog/shipping/quote").permitAll()
                        // Las sugerencias de ahorro devuelven productos del catálogo y cotizan envíos:
                        // se cierran igual que el listado, y por el mismo motivo —no regalar el catálogo
                        // ni el trabajo del transportista a quien no tiene cuenta.
                        .requestMatchers(HttpMethod.POST, "/api/catalog/cart-suggestions").authenticated()
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
                        .requestMatchers(HttpMethod.POST, API_CONTACT).permitAll()
                        // OPERATOR (soporte) SOLO puede: procesar órdenes (avanzar/enviar/entregar) y ver sus
                        // propias ganancias/historial. Las mutaciones con impacto FINANCIERO o de CREACIÓN de
                        // pedidos —cancelar, reembolsar (al wallet/tarjeta), crear e importar— son EXCLUSIVAS de
                        // ADMIN: sin este gate por método, el gate por URL /api/admin/orders/** dejaba a un
                        // OPERATOR emitir reembolsos masivos. Estas reglas MÁS ESPECÍFICAS van antes que la general.
                        .requestMatchers(HttpMethod.POST,
                                "/api/admin/orders",
                                "/api/admin/orders/demo",
                                "/api/admin/orders/import",
                                "/api/admin/orders/*/cancel",
                                "/api/admin/orders/*/refund",
                                "/api/admin/orders/bulk-cancel",
                                "/api/admin/orders/bulk-refund").hasRole(ADMIN)
                        // Todo lo demás del admin (pricing/márgenes, dashboard/estadísticas, catálogo, usuarios,
                        // monedas, impuestos, partners, billing, afiliados…) es EXCLUSIVO de ADMIN.
                        .requestMatchers("/api/admin/orders/**").hasAnyRole(ADMIN, "OPERATOR")
                        .requestMatchers("/api/admin/operator/**").hasAnyRole(ADMIN, "OPERATOR")
                        .requestMatchers("/api/admin/**").hasRole(ADMIN)
                        // Envío de cotizaciones de sourcing = operación de AGENTE/soporte, NO de cliente. Vivía
                        // bajo /api/me/** (solo "authenticated") sin comprobar rol y aceptando ?asAgent=<id>, así
                        // que cualquier usuario podía inyectar cotizaciones falsas en la petición de otro e
                        // IMPERSONAR a cualquier agente. Se restringe a ADMIN/OPERATOR. El cliente solo crea la
                        // petición y SELECCIONA la cotización ganadora (esas rutas siguen siendo suyas).
                        .requestMatchers(HttpMethod.POST, "/api/me/sourcing/requests/*/quotes")
                        .hasAnyRole(ADMIN, "OPERATOR")
                        // /api/me is the auth-bootstrap probe — it must succeed even when
                        // unauthenticated (the controller returns null), otherwise the SPA
                        // sees a noisy 401 on every cold load before login.
                        .requestMatchers(HttpMethod.GET, "/api/me").permitAll().requestMatchers("/api/me/**")
                        .authenticated().anyRequest().authenticated())
                .oauth2ResourceServer(
                        oauth -> oauth.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter)))
                // Revoca en caliente los tokens de usuario (p.ej. tras un cambio de rol): un access token
                // de 60 min con el rol viejo se corta en la siguiente petición en vez de seguir válido.
                .addFilterBefore(userTokenRevocationFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
