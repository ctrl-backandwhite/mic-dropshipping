package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PublicHttpUrl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El servidor no llama a direcciones internas aunque se las pidan.
 *
 * <p>Las direcciones de los webhooks las registra el propio partner, y se llamaban sin comprobar nada.
 * Apuntando a 169.254.169.254 —el servicio de metadatos de la nube, que devuelve las credenciales de la
 * instancia— o a un servicio de la red interna, el servidor hacía la petición desde dentro; y como el
 * cuerpo de la respuesta se guarda en el registro de entregas, el partner podía además leerlo. Es la
 * forma habitual de convertir un webhook en una puerta trasera.
 */
@DisplayName("Sólo se llama a direcciones públicas de Internet")
class PublicHttpUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254/latest/meta-data/", // metadatos de la nube: credenciales de la instancia
            "http://127.0.0.1:18082/api/admin/orders", // la propia API, saltándose la autenticación de red
            "http://localhost:5432/", // la base de datos
            "http://10.0.0.5/interno", // red privada
            "http://192.168.1.10/router", // red doméstica
            "http://172.16.0.1/", // red privada
            "http://100.64.0.1/", // CGNAT, que isSiteLocalAddress no cubre
            "http://[::1]:8080/", // bucle local en IPv6
    })
    void seRechazaCualquierDireccionQueNoSalgaDeLaRed(String url) {
        assertThatThrownBy(() -> PublicHttpUrl.assertPublic(URI.create(url))).isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", // leer ficheros del servidor
            "ftp://interno/backup", "gopher://127.0.0.1:6379/", // el clásico para hablar con Redis
            "jar:file:///tmp/x.jar!/",})
    void seRechazaTodoLoQueNoSeaHttpOHttps(String url) {
        assertThatThrownBy(() -> PublicHttpUrl.assertPublic(URI.create(url))).isInstanceOf(SecurityException.class)
                .hasMessageContaining("Esquema");
    }

    @Test
    void seRechazaUnaUrlSinHost() {
        assertThatThrownBy(() -> PublicHttpUrl.assertPublic(URI.create("http:///sin-host")))
                .isInstanceOf(SecurityException.class).hasMessageContaining("sin host");
    }

    @Test
    void unaDireccionPublicaSePermite() {
        // 8.8.8.8 es pública y no requiere resolver ningún nombre, así que el test no depende de la red.
        assertThat(PublicHttpUrl.isPublic("https://8.8.8.8/webhook")).isTrue();
    }

    @Test
    void unNombreQueNoSeResuelveNoSeDaPorBueno() {
        assertThat(PublicHttpUrl.isPublic("https://este-dominio-no-existe.invalid/webhook")).isFalse();
    }

    @Test
    void unaUrlMalFormadaNoSeDaPorBuena() {
        assertThat(PublicHttpUrl.isPublic("no es una url")).isFalse();
    }
}
