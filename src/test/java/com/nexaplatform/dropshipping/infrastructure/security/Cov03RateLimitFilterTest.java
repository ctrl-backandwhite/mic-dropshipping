package com.nexaplatform.dropshipping.infrastructure.security;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Cortafuegos de peticiones. Aquí se decide quién consume cubo: sin estas reglas, la fuerza bruta contra
 * el login y el volcado masivo del catálogo pasan sin freno, y la cuota por IP se esquiva simplemente
 * enviando una cabecera {@code X-Forwarded-For} falsa.
 */
class Cov03RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void preparaFiltro() {
        filter = new RateLimitFilter();
        chain = mock(FilterChain.class);
    }

    private MockHttpServletResponse lanza(String path, String ip, String forwardedFor, String bearer) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
        req.setRequestURI(path);
        req.setRemoteAddr(ip);
        if (forwardedFor != null) {
            req.addHeader("X-Forwarded-For", forwardedFor);
        }
        if (bearer != null) {
            req.addHeader("Authorization", "Bearer " + bearer);
        }
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, chain);
        return res;
    }

    private MockHttpServletResponse lanza(String path, String ip) throws Exception {
        return lanza(path, ip, null, null);
    }

    private static String jwt(String sub, String plan) {
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(sub);
        if (plan != null) {
            claims.claim("plan", plan);
        }
        return new PlainJWT(claims.build()).serialize();
    }

    /* ==================== qué rutas llevan cubo ==================== */

    @Test
    void unaRutaSinReglaPasaSinConsumirCuotaYSinCabecerasDeLimite() throws Exception {
        MockHttpServletResponse res = lanza("/actuator/health", "10.0.0.1");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("RateLimit-Limit")).isNull();
    }

    @ParameterizedTest
    @CsvSource({"/api/auth/login,                    auth.login.api,  10",
            "/api/auth/refresh,                  auth.refresh,    30",
            "/api/auth/register,                 auth.register,   5",
            "/api/auth/password-reset/request,   auth.reset.req,  20",
            "/api/auth/password-reset/confirm,   auth.reset.conf, 5",
            "/login,                             auth.login,      20",
            "/oauth2/token,                      oauth.token,     30",
            "/api/chat,                          chat.ask,        15",
            "/api/catalog/cart-suggestions,      cart.suggestions, 20",
            "/api/catalog/products,              storefront.web,  100",
            "/api/search,                        storefront.web,  100",
            "/api/v1/rate-limits,                storefront,      60",
            "/api/v1/invoices/1,                 storefront,      60",
            "/api/v1/integrations/shops/7/hook,  inbound.shop,    240"})
    void cadaRutaCaeEnSuPoliticaYConSuCuota(String path, String politica, int capacidad) throws Exception {
        // El orden de evaluación importa: si las reglas de autenticación no fueran primero, /api/auth/login
        // caería en el cubo público de 100/min y el credential-stuffing quedaría prácticamente sin freno.
        MockHttpServletResponse res = lanza(path, "10.0.0.1");

        assertThat(res.getHeader("X-RateLimit-Policy")).isEqualTo(politica);
        assertThat(res.getHeader("RateLimit-Limit")).isEqualTo(String.valueOf(capacidad));
        assertThat(res.getHeader("RateLimit-Remaining")).isEqualTo(String.valueOf(capacidad - 1));
        assertThat(res.getHeader("RateLimit-Reset")).isNotNull();
    }

    /* ==================== respuesta al agotar la cuota ==================== */

    @Test
    void alAgotarLaCuotaSeResponde429ConLaPoliticaYElTiempoDeEspera() throws Exception {
        for (int i = 0; i < 5; i++) {
            lanza("/api/auth/register", "10.0.0.1");
        }

        MockHttpServletResponse res = lanza("/api/auth/register", "10.0.0.1");

        assertThat(res.getStatus()).isEqualTo(429);
        assertThat(Integer.parseInt(res.getHeader("Retry-After"))).isPositive();
        assertThat(res.getContentType()).isEqualTo("application/json");
        assertThat(res.getContentAsString()).contains("\"code\":\"RATE_LIMITED\"").contains("auth.register");
        assertThat(res.getHeader("RateLimit-Remaining")).isEqualTo("0");
    }

    /* ==================== IP real detrás de proxy ==================== */

    @Test
    void laIpDeCuotaEsLaQueAnadeElProxyDeConfianzaYNoLaQueDiceElCliente() throws Exception {
        // Con un proxy delante, la primera entrada de X-Forwarded-For la escribe el propio cliente: si se
        // usara esa, bastaría con cambiarla en cada petición para no agotar nunca la cuota.
        for (int i = 0; i < 5; i++) {
            lanza("/api/auth/register", "192.168.0.1", "1.1.1." + i + ", 8.8.8.8", null);
        }

        MockHttpServletResponse res = lanza("/api/auth/register", "192.168.0.1", "9.9.9.9, 8.8.8.8", null);

        assertThat(res.getStatus()).isEqualTo(429);
    }

    @Test
    void conDosProxysDeConfianzaSeRetrocedenDosSaltos() throws Exception {
        ReflectionTestUtils.setField(filter, "trustedProxyCount", 2);

        for (int i = 0; i < 5; i++) {
            lanza("/api/auth/register", "192.168.0.1", "7.7.7.7, 8.8.8.8", null);
        }

        // Mismo salto de confianza (7.7.7.7) -> mismo cubo, ya agotado.
        assertThat(lanza("/api/auth/register", "192.168.0.1", "7.7.7.7, 9.9.9.9", null).getStatus()).isEqualTo(429);
        // Otro cliente en ese salto -> cubo propio.
        assertThat(lanza("/api/auth/register", "192.168.0.1", "6.6.6.6, 8.8.8.8", null).getStatus()).isEqualTo(200);
    }

    @Test
    void unaCabeceraDeReenvioVaciaODegeneradaNoRevientaElFiltro() throws Exception {
        // split(",") sobre "," devuelve un array VACÍO: sin el corte, el acceso por índice lanzaba
        // ArrayIndexOutOfBounds DENTRO del filtro, es decir un 500 en cada petición.
        assertThat(lanza("/api/auth/register", "10.0.0.9", ",,,", null).getStatus()).isEqualTo(200);
        assertThat(lanza("/api/auth/register", "10.0.0.9", "   ", null).getStatus()).isEqualTo(200);
        assertThat(lanza("/api/auth/register", "10.0.0.9", ", ", null).getStatus()).isEqualTo(200);
        // Las tres han caído en el MISMO cubo (el de la IP de la conexión): la cuarta y la quinta también.
        lanza("/api/auth/register", "10.0.0.9", null, null);
        lanza("/api/auth/register", "10.0.0.9", null, null);
        assertThat(lanza("/api/auth/register", "10.0.0.9", null, null).getStatus()).isEqualTo(429);
    }

    /* ==================== cuota del partner por plan ==================== */

    @Test
    void elPartnerDePruebasTieneUnaPeticionPorMinutoYElDePagoCinco() throws Exception {
        String sandbox = jwt("cliente-sandbox", "sandbox");
        String pago = jwt("cliente-pago", "paid");

        assertThat(lanza("/api/v1/partner/catalog/products", "10.0.0.1", null, sandbox).getStatus()).isEqualTo(200);
        assertThat(lanza("/api/v1/partner/catalog/products", "10.0.0.1", null, sandbox).getStatus()).isEqualTo(429);

        for (int i = 0; i < 5; i++) {
            assertThat(lanza("/api/v1/partner/catalog/products", "10.0.0.1", null, pago).getStatus()).isEqualTo(200);
        }
        assertThat(lanza("/api/v1/partner/catalog/products", "10.0.0.1", null, pago).getStatus()).isEqualTo(429);
    }

    @Test
    void sinPlanEnElTokenSeAplicaLaCuotaDePruebas() throws Exception {
        String sinPlan = jwt("cliente-x", null);

        assertThat(lanza("/api/v1/partner/orders", "10.0.0.1", null, sinPlan).getStatus()).isEqualTo(200);
        assertThat(lanza("/api/v1/partner/orders", "10.0.0.1", null, sinPlan).getStatus()).isEqualTo(429);
    }

    @Test
    void dosPartnersDistintosNoSeGastanLaCuotaElUnoAlOtroAunqueCompartanIp() throws Exception {
        assertThat(lanza("/api/v1/partner/shop/sync", "10.0.0.1", null, jwt("uno", "sandbox")).getStatus())
                .isEqualTo(200);
        assertThat(lanza("/api/v1/partner/shop/sync", "10.0.0.1", null, jwt("dos", "sandbox")).getStatus())
                .isEqualTo(200);
    }

    @Test
    void unTokenIlegibleNoTumbaLaPeticionYSeLimitaPorIp() throws Exception {
        // Un Authorization mal formado no puede convertirse en un 500: se ignora y se cae al cubo por IP.
        MockHttpServletResponse res = lanza("/api/v1/partner/catalog/x", "10.0.0.1", null, "esto-no-es-un-jwt");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("RateLimit-Limit")).isEqualTo("1"); // sin plan legible -> cuota de pruebas
    }

    /* ==================== webhooks entrantes por tienda ==================== */

    @Test
    void cadaTiendaConectadaTieneSuPropioCuboDeWebhooks() throws Exception {
        // Un comercio con mucho tráfico no puede dejar sin webhooks a los demás.
        for (int i = 0; i < 240; i++) {
            lanza("/api/v1/integrations/shops/tienda-a/orders", "10.0.0.1");
        }

        assertThat(lanza("/api/v1/integrations/shops/tienda-a/orders", "10.0.0.1").getStatus()).isEqualTo(429);
        assertThat(lanza("/api/v1/integrations/shops/tienda-b/orders", "10.0.0.1").getStatus()).isEqualTo(200);
    }

    @Test
    void unaUrlDeWebhookSinIdentificadorDeTiendaSeLimitaPorIp() throws Exception {
        MockHttpServletResponse res = lanza("/api/v1/integrations/shops/", "10.0.0.1");

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getHeader("X-RateLimit-Policy")).isEqualTo("inbound.shop");
    }

    /* ==================== catálogo de políticas publicado ==================== */

    @Test
    void elCatalogoDePoliticasPublicaTodasLasReglasVigentes() {
        // Es documentación viva para los integradores: una regla que existe pero no se publica hace que
        // el cliente descubra su límite a base de 429.
        List<Map<String, Object>> politicas = filter.policies();

        assertThat(politicas).extracting(p -> p.get("name")).containsExactlyInAnyOrder("partner.catalog.read",
                "partner.orders.write", "partner.shop.sync", "inbound.shop", "storefront", "storefront.web",
                "oauth.token", "auth.login", "auth.login.api", "auth.refresh", "auth.register", "auth.reset.req",
                "auth.reset.conf", "chat.ask", "cart.suggestions");
    }

    @Test
    void lasPoliticasDePartnerPublicanSuCuotaPorPlan() {
        Map<String, Object> partner = filter.policies().stream()
                .filter(p -> "partner.catalog.read".equals(p.get("name"))).findFirst().orElseThrow();

        assertThat(partner).containsEntry("tiers", Map.of("sandbox", 1, "paid", 5)).containsEntry("period", "1m");
    }

    @Test
    void reiniciarElFiltroDevuelveLaCuotaCompleta() throws Exception {
        for (int i = 0; i < 5; i++) {
            lanza("/api/auth/register", "10.0.0.1");
        }
        assertThat(lanza("/api/auth/register", "10.0.0.1").getStatus()).isEqualTo(429);

        filter.reset();

        assertThat(lanza("/api/auth/register", "10.0.0.1").getStatus()).isEqualTo(200);
    }
}
