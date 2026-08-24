package com.nexaplatform.dropshipping.infrastructure.http;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Qué puede guardar un intermediario de las respuestas públicas del catálogo, y bajo qué clave.
 *
 * <p>No es una cuestión de rendimiento: la misma URL devuelve <b>precios distintos</b> según la moneda y el
 * país del comprador —el margen se aplica por país— y, desde la agrupación arancelaria, también una promesa
 * de aduana distinta. Una cabecera {@code Vary} incompleta autoriza a un CDN a servirle a un comprador la
 * respuesta que se calculó para otro país.
 */
class HttpCacheFilterTest {

    private final HttpCacheFilter filter = new HttpCacheFilter();

    @Test
    void elCatalogoVariaPorMonedaYPorPais() throws Exception {
        MockHttpServletResponse res = respuestaDe("GET", "/api/catalog/products");

        assertThat(res.getHeader("Vary")).contains("X-Currency").contains("X-Country");
    }

    @Test
    void loQueNoEsPublicoNoSeGuarda() throws Exception {
        MockHttpServletResponse res = respuestaDe("GET", "/api/me/orders");

        assertThat(res.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(res.getHeader("Vary")).isNull();
    }

    private MockHttpServletResponse respuestaDe(String metodo, String ruta) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(metodo, ruta);
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, mock(FilterChain.class));
        return res;
    }
}
