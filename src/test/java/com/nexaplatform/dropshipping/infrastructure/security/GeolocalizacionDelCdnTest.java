package com.nexaplatform.dropshipping.infrastructure.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cuándo puede creerse el país que dice el CDN.
 *
 * <p>Lo que se rompe si esto falla: el servidor de origen responde 200 si se le llama directamente con
 * {@code --resolve} y el {@code Host} correcto, saltándose Cloudflare por completo —comprobado el
 * 27-ago-2026 y de nuevo el 4-sep—. Sin esta comprobación, {@code CF-IPCountry} es una cabecera como
 * cualquier otra y quien alcanza el origen se declara del país que le convenga: el país decide el margen
 * y los costes de aduana, y en el alta social decide el país que queda GRABADO en la ficha, que es la
 * fuente en la que más se confía después.
 */
@DisplayName("Geolocalización del CDN · solo si la petición demuestra venir de él")
class GeolocalizacionDelCdnTest {

    private static final String SECRETO = "un-secreto-de-borde-largo-y-aleatorio";

    private GeolocalizacionDelCdn conSecreto() {
        GeolocalizacionDelCdn g = new GeolocalizacionDelCdn();
        ReflectionTestUtils.setField(g, "secreto", SECRETO);
        return g;
    }

    @Test
    @DisplayName("sin secreto configurado se confía en la cabecera, como hasta ahora")
    void sinSecretoSeConfia() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-IPCountry", "US");

        assertThat(new GeolocalizacionDelCdn().paisDeConfianza(req)).isEqualTo("US");
    }

    @Test
    @DisplayName("con secreto, una petición SIN la cabecera del borde no aporta país")
    void conSecretoYSinCabeceraDelBordeNoHayPais() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("CF-IPCountry", "US");

        assertThat(conSecreto().paisDeConfianza(req)).isNull();
    }

    @Test
    @DisplayName("con el secreto correcto sí se lee el país")
    void conElSecretoCorrectoSeLeeElPais() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(GeolocalizacionDelCdn.HEADER_CDN, SECRETO);
        req.addHeader("CF-IPCountry", "US");

        assertThat(conSecreto().paisDeConfianza(req)).isEqualTo("US");
    }

    @Test
    @DisplayName("un secreto equivocado no vale, ni siquiera acertando el principio")
    void unSecretoEquivocadoNoVale() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader(GeolocalizacionDelCdn.HEADER_CDN, SECRETO.substring(0, SECRETO.length() - 1));
        req.addHeader("CF-IPCountry", "US");

        assertThat(conSecreto().paisDeConfianza(req)).isNull();
    }

    /** Los CDN mandan "XX" o "T1" cuando no saben el país; eso no es un país. */
    @Test
    @DisplayName("país desconocido o Tor se ignoran")
    void paisDesconocidoSeIgnora() {
        for (String valor : new String[]{"XX", "T1", "E", "ESP", ""}) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader(GeolocalizacionDelCdn.HEADER_CDN, SECRETO);
            req.addHeader("CF-IPCountry", valor);
            assertThat(conSecreto().paisDeConfianza(req)).as("valor %s", valor).isNull();
        }
    }

    /** Las cuatro cabeceras equivalentes valen igual: el CDN puede no ser Cloudflare. */
    @Test
    @DisplayName("sirve cualquiera de las cabeceras de geolocalización conocidas")
    void sirveCualquieraDeLasCabecerasConocidas() {
        for (String h : new String[]{"CF-IPCountry", "X-Vercel-IP-Country", "X-Geo-Country", "X-Country-Code"}) {
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader(GeolocalizacionDelCdn.HEADER_CDN, SECRETO);
            req.addHeader(h, "FR");
            assertThat(conSecreto().paisDeConfianza(req)).as("cabecera %s", h).isEqualTo("FR");
        }
    }
}
