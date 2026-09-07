package com.nexaplatform.dropshipping.infrastructure.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * La exención de la COMPILACIÓN del front.
 *
 * <p>Prerenderizar fichas es, visto desde el limitador, exactamente lo que la regla del escaparate
 * existe para frenar: un volcado del catálogo a toda velocidad. Con 100 peticiones por minuto no
 * cabían más de unas quince fichas, y las demás se escribían con una página de error dentro mientras
 * la compilación terminaba en verde.
 *
 * <p>Lo que se certifica aquí no es solo que la exención funcione, sino DÓNDE deja de funcionar: es
 * una puerta en la defensa contra el volcado del catálogo y solo puede abrirse en un sitio.
 */
class RateLimitBuildExemptionTest {

    private static final String TESTIGO = "testigo-de-compilacion-0123456789";
    private static final String CABECERA = "X-Prerender-Token";
    private static final String FICHA = "/api/catalog/products/gorro-de-lana";

    private RateLimitFilter filtro;

    @BeforeEach
    void setUp() {
        filtro = new RateLimitFilter();
        ReflectionTestUtils.setField(filtro, "buildToken", TESTIGO);
        ReflectionTestUtils.setField(filtro, "buildCapacity", 1200L);
    }

    /** Devuelve el estado de la respuesta tras pasar la petición por el filtro. */
    private MockHttpServletResponse pasa(String metodo, String camino, String testigo) throws Exception {
        MockHttpServletRequest peticion = new MockHttpServletRequest(metodo, camino);
        peticion.setRemoteAddr("10.9.9.9");
        if (testigo != null) {
            peticion.addHeader(CABECERA, testigo);
        }
        MockHttpServletResponse respuesta = new MockHttpServletResponse();
        filtro.doFilter(peticion, respuesta, mock(FilterChain.class));
        return respuesta;
    }

    @Test
    @DisplayName("con el testigo correcto la compilación pasa de las 100 del escaparate")
    void con_testigo_pasa_del_cupo_del_escaparate() throws Exception {
        for (int i = 0; i < 150; i++) {
            assertThat(pasa("GET", FICHA, TESTIGO).getStatus())
                    .as("cortada en la petición %d, que es justo lo que se venía a arreglar", i)
                    .isEqualTo(200);
        }
        assertThat(pasa("GET", FICHA, TESTIGO).getHeader("X-RateLimit-Policy")).isEqualTo("build.prerender");
    }

    /** Sigue siendo un cupo, no la ausencia de límite: el día que el testigo se filtre, eso es lo que queda. */
    @Test
    @DisplayName("el cupo de la compilación también se acaba")
    void el_cupo_de_la_compilacion_tambien_se_acaba() throws Exception {
        ReflectionTestUtils.setField(filtro, "buildCapacity", 3L);

        for (int i = 0; i < 3; i++) {
            assertThat(pasa("GET", FICHA, TESTIGO).getStatus()).isEqualTo(200);
        }

        assertThat(pasa("GET", FICHA, TESTIGO).getStatus()).isEqualTo(429);
    }

    @Test
    @DisplayName("sin testigo se sigue aplicando el límite del escaparate")
    void sin_testigo_manda_el_escaparate() throws Exception {
        for (int i = 0; i < 100; i++) {
            assertThat(pasa("GET", FICHA, null).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse cortada = pasa("GET", FICHA, null);
        assertThat(cortada.getStatus()).isEqualTo(429);
        assertThat(cortada.getHeader("X-RateLimit-Policy")).isEqualTo("storefront.web");
    }

    @Test
    @DisplayName("un testigo que no es el bueno no exime de nada")
    void testigo_equivocado_no_exime() throws Exception {
        for (int i = 0; i < 100; i++) {
            pasa("GET", FICHA, "no-es-este");
        }

        assertThat(pasa("GET", FICHA, "no-es-este").getStatus()).isEqualTo(429);
    }

    /**
     * El testigo no puede abrir un camino que ESCRIBA. Si abriera, dejaría de ser una palanca de cupo
     * para convertirse en una llave: cualquiera con él podría crear cuentas sin freno.
     */
    @Test
    @DisplayName("el testigo no sirve para nada que no sea un GET")
    void el_testigo_no_vale_para_escribir() throws Exception {
        for (int i = 0; i < 5; i++) {
            assertThat(pasa("POST", "/api/auth/register", TESTIGO).getStatus()).isEqualTo(200);
        }

        MockHttpServletResponse cortada = pasa("POST", "/api/auth/register", TESTIGO);
        assertThat(cortada.getStatus()).isEqualTo(429);
        assertThat(cortada.getHeader("X-RateLimit-Policy")).isEqualTo("auth.register");
    }

    /** Y tampoco un camino fuera del escaparate: la autenticación conserva sus propias reglas. */
    @Test
    @DisplayName("el testigo no toca las reglas de autenticación")
    void el_testigo_no_toca_la_autenticacion() throws Exception {
        for (int i = 0; i < 10; i++) {
            assertThat(pasa("GET", "/api/auth/login", TESTIGO).getStatus()).isEqualTo(200);
        }

        assertThat(pasa("GET", "/api/auth/login", TESTIGO).getStatus()).isEqualTo(429);
    }

    /** Sin testigo configurado la puerta ni existe: es el estado de cualquier entorno que no compile. */
    @Test
    @DisplayName("con el testigo sin configurar, la cabecera no vale nada")
    void sin_configurar_la_cabecera_no_vale() throws Exception {
        ReflectionTestUtils.setField(filtro, "buildToken", "");

        for (int i = 0; i < 100; i++) {
            pasa("GET", FICHA, "");
        }

        assertThat(pasa("GET", FICHA, "").getStatus()).isEqualTo(429);
    }
}
