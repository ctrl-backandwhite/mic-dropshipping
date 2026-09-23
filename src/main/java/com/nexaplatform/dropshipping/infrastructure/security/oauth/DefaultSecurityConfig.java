package com.nexaplatform.dropshipping.infrastructure.security.oauth;

import com.nexaplatform.dropshipping.application.service.DeviceSessionService;
import com.nexaplatform.dropshipping.application.usecase.UserUseCase;
import com.nexaplatform.dropshipping.infrastructure.security.GeolocalizacionDelCdn;
import com.nexaplatform.dropshipping.infrastructure.security.jwt.UserTokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

@Configuration
// Los @PreAuthorize de los controladores de administración NO se ejecutaban: sin esto Spring no crea los
// proxies de seguridad de método y la anotación es texto. Las tres que hay expresan lo mismo que ya impone
// la cadena por URL (/api/admin/** exige ADMIN), así que activarlo no cambia quién entra hoy; lo que
// cambia es que la próxima deje de ser decorativa.
@EnableMethodSecurity
public class DefaultSecurityConfig {

    // Literales repetidos extraídos a constantes (java:S1192): una sola fuente por valor.
    private static final String LOGIN = "/login";

    @Value("${nexadrop.storefront.base-url}")
    private String frontBaseUrl;

    /**
     * Enlace profundo con el que la aplicación móvil recupera el control tras el login social. Es un
     * esquema propio ({@code nx036://}) porque el sistema operativo solo sabe devolver el foco a la
     * aplicación por él; la web no lo entiende y por eso no puede compartir destino.
     */
    @Value("${nexadrop.mobile.oauth-callback-url}")
    private String mobileOauthCallbackUrl;

    /**
     * Se reutiliza el mismo interruptor que la cookie de sesión ({@code false} en local sobre HTTP,
     * {@code true} en dev/pre/pro sobre HTTPS). Así la cookie CSRF y la de sesión no se pueden desalinear:
     * marcar {@code Secure} a mano en local haría que el navegador descartara la cookie sobre HTTP y el
     * formulario de login dejaría de validar el token.
     */
    @Value("${server.servlet.session.cookie.secure:false}")
    private boolean secureCookies;

    /**
     * Repositorio del token CSRF (patrón de doble envío) con los atributos de cookie que faltaban.
     *
     * <p>OWASP ZAP levantó «cookie sin atributo SameSite» en {@code /login}. La cookie señalada NO es la de
     * sesión —{@code JSESSIONID} ya sale con {@code SameSite=Lax} y {@code HttpOnly} por
     * {@code server.servlet.session.cookie.*}—, sino {@code XSRF-TOKEN}: Spring Security construye esa
     * cookie sin {@code SameSite} salvo que se le pase un customizer, y {@code login.html} la provoca al
     * renderizar el campo {@code _csrf}.
     *
     * <p>Se fija {@code SameSite=Lax} (el navegador no la manda en peticiones cross-site con efectos, que
     * es justo lo que quita valor a robarla) y {@code Secure} donde hay HTTPS. Sigue SIN {@code HttpOnly}
     * a propósito: el patrón de doble envío exige que el JavaScript del cliente pueda leer el token para
     * reenviarlo en la cabecera {@code X-XSRF-TOKEN}. Es el token CSRF, no la sesión.
     */
    CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse(); // NOSONAR java:S3330 — legible por diseño del patrón de doble envío
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Lax").secure(secureCookies));
        return repository;
    }

    @Bean
    public OAuthRedirectResolver oauthRedirectResolver() {
        return new OAuthRedirectResolver(frontBaseUrl, mobileOauthCallbackUrl);
    }

    /**
     * Descodificador del ID token de los logins sociales. Spring lo recoge de aquí por su tipo
     * ({@code OAuth2LoginConfigurer.getJwtDecoderFactoryBean()}) y con él sustituye al suyo, que descarga
     * las claves públicas del proveedor en cada intento y solo le concede medio segundo —de ahí que el
     * acceso con Google fallara de forma intermitente—. El detalle está en
     * {@link ResilientOidcIdTokenDecoderFactory}.
     */
    @Bean
    public JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        return new ResilientOidcIdTokenDecoderFactory();
    }

    @Bean
    public OAuthLoginFailureHandler oauthLoginFailureHandler(OAuthRedirectResolver oauthRedirectResolver) {
        return new OAuthLoginFailureHandler(oauthRedirectResolver);
    }

    @Bean
    public GoogleOAuth2SuccessHandler googleOAuth2SuccessHandler(UserUseCase userUseCase,
            UserTokenService userTokenService, DeviceSessionService deviceSessionService,
            com.nexaplatform.dropshipping.application.service.TotpService totpService,
            OAuthRedirectResolver oauthRedirectResolver, GeolocalizacionDelCdn geolocalizacionDelCdn) {
        return new GoogleOAuth2SuccessHandler(userUseCase, userTokenService, deviceSessionService, totpService,
                oauthRedirectResolver, geolocalizacionDelCdn);
    }

    @Bean
    @Order(3)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http,
            GoogleOAuth2SuccessHandler googleOAuth2SuccessHandler, GithubOAuth2UserService githubOAuth2UserService,
            OAuthLoginFailureHandler oauthLoginFailureHandler) {
        // Instancia local y no un @Bean: Spring Boot registra automáticamente en la cadena de filtros
        // del contenedor cualquier bean de tipo Filter, con lo que este actuaría también sobre
        // peticiones ajenas a esta cadena de seguridad. Aquí solo lo usa quien debe.
        OAuthClientTargetFilter oauthClientTargetFilter = new OAuthClientTargetFilter();
        http.cors(Customizer.withDefaults())
                // ÚNICA cadena con sesión y formulario, y por eso la ÚNICA con CSRF ACTIVO. Las otras tres
                // (authorization server, partners y BFF) son stateless con Bearer y ahí sí se desactiva.
                // Los atributos de la cookie XSRF-TOKEN (SameSite/Secure) se fijan en csrfTokenRepository().
                // NOSONAR java:S4502 — CSRF habilitado en esta cadena.
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository())
                        // Exenciones: ninguna de estas rutas se autentica por COOKIE, que es lo que CSRF
                        // protege. /oauth2/token la resuelve la cadena del authorization server con
                        // client_secret_basic; /login/oauth2/code/** es el callback GET del proveedor;
                        // /api/v1/rate-limits y /api/v1/invoices son GET públicos (docs y verificación de
                        // factura); /api/v1/integrations/** y /api/webhooks/** son pushes entrantes con
                        // firma propia (HMAC del comercio, SHA-256 de YunExpress, firma de Stripe/PayPal),
                        // que un navegador no puede fabricar.
                        //
                        // /actuator/** depende de una INVARIANTE: management.endpoints.web.exposure.include
                        // solo publica health, info, metrics y prometheus, todos GET —y CSRF nunca exige
                        // token en métodos seguros—. Si algún día se expone un endpoint con efectos
                        // (loggers, shutdown, env POST), hay que sacar /actuator/** de esta lista: en esta
                        // cadena se autentica por sesión y quedaría expuesto a CSRF.
                        .ignoringRequestMatchers("/oauth2/token", "/login/oauth2/code/**", "/actuator/**", // NOSONAR java:S4502 — rutas sin sesión: OAuth2, callbacks y webhooks con firma propia
                                "/api/v1/rate-limits/**", "/api/v1/invoices/**", "/api/v1/integrations/**",
                                "/api/webhooks/**"))
                .headers(h -> h
                        .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(31536000))
                        .frameOptions(fo -> fo.deny())
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .authorizeHttpRequests(reg -> reg
                        .requestMatchers("/", LOGIN, "/login/**", "/register", "/activate", "/activate/**",
                                "/password-reset", "/password-reset/**", "/error", "/.well-known/**", "/oauth2/**",
                                "/userinfo", "/actuator/health", "/actuator/info",
                                // Public storefront catalog + signed inbound webhooks + payment callbacks.
                                "/api/v1/rate-limits/**", "/api/v1/invoices/**", "/api/v1/integrations/**",
                                "/api/webhooks/**", "/css/**", "/js/**", "/img/**", "/assets/**", "/favicon.ico")
                        .permitAll()
                        // Swagger UI + OpenAPI JSON quedan tras login y solo accesibles a staff.
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/v3/api-docs.yaml", "/v3/api-docs.yaml/**",
                                "/swagger-ui.html", "/swagger-ui/**", "/swagger-resources/**", "/webjars/**")
                        .hasAnyAuthority("ROLE_ADMIN", "ROLE_OPERATOR")
                        // El resto de actuator (metrics/prometheus) NO debe quedar visible a cualquier usuario
                        // autenticado: solo ADMIN. health/info siguen públicos (arriba).
                        .requestMatchers("/actuator/**").hasAuthority("ROLE_ADMIN").anyRequest().authenticated())
                .formLogin(form -> form.loginPage(LOGIN).permitAll()).oauth2Login(oauth -> oauth.loginPage(LOGIN)
                        // GitHub no habla OIDC, así que su email verificado lo resuelve nuestro propio
                        // servicio de usuario. Google sí lo habla y se queda con el que trae Spring.
                        .userInfoEndpoint(userInfo -> userInfo.userService(githubOAuth2UserService))
                        .successHandler(googleOAuth2SuccessHandler)
                        // El fallo vuelve al cliente que arrancó (si no, la aplicación móvil se quedaría
                        // esperando en el navegador del sistema sin recuperar el foco) y, sobre todo, queda
                        // anotado en el registro con el motivo que da el proveedor.
                        .failureHandler(oauthLoginFailureHandler).permitAll())
                // Anota el cliente de origen ANTES de que Spring redirija al proveedor; después ya no
                // habría ocasión, porque la vuelta llega en otra petición.
                .addFilterBefore(oauthClientTargetFilter, OAuth2AuthorizationRequestRedirectFilter.class);
        return http.build();
    }
}
