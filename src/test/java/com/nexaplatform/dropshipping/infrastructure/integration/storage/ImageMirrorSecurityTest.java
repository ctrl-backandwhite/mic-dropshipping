package com.nexaplatform.dropshipping.infrastructure.integration.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Endurecimiento de seguridad del mirror de imágenes:
 *  - {@link ImageMirrorService#assertPublicHttpUrl(URI)} bloquea SSRF (IP internas/metadata, esquemas raros).
 *  - {@link ImageMirrorService#sniffRasterImage(byte[])} solo acepta imágenes ráster reales (descarta SVG/HTML).
 */
class ImageMirrorSecurityTest {

    // ---- Anti-SSRF ----

    /**
     * Destinos que un atacante usaría para que el servidor le lea algo de dentro de la red. La URL se
     * construye FUERA de la lambda: dentro habría dos llamadas capaces de lanzar y un fallo al analizar
     * la dirección daría el test por bueno sin haber ejercitado la comprobación.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/x.jpg",
            "http://169.254.169.254/latest/meta-data",   // endpoint de metadata del cloud (link-local)
            "http://10.0.0.5/x.jpg",
            "http://192.168.1.10/x.jpg"
    })
    void rejects_loopback_and_metadata_and_private_ips(String url) {
        URI uri = URI.create(url);
        assertThatThrownBy(() -> ImageMirrorService.assertPublicHttpUrl(uri))
                .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "gopher://x/"})
    void rejects_non_http_schemes(String url) {
        URI uri = URI.create(url);
        assertThatThrownBy(() -> ImageMirrorService.assertPublicHttpUrl(uri))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void allows_public_host() {
        // Host público real (literal IP pública para no depender de DNS variable).
        assertThatCode(() -> ImageMirrorService.assertPublicHttpUrl(URI.create("https://1.1.1.1/x.jpg")))
                .doesNotThrowAnyException();
    }

    // ---- Anti-XSS por content-type (magic bytes) ----

    @Test
    void accepts_real_raster_images_by_magic_bytes() {
        assertThat(ImageMirrorService.sniffRasterImage(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00}))
                .isEqualTo("jpg");
        assertThat(ImageMirrorService.sniffRasterImage(new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .isEqualTo("png");
        assertThat(ImageMirrorService.sniffRasterImage(new byte[] {'G', 'I', 'F', '8', '9', 'a'})).isEqualTo("gif");
        assertThat(ImageMirrorService.sniffRasterImage(
                new byte[] {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'})).isEqualTo("webp");
    }

    @Test
    void rejects_svg_and_html_disguised_as_image() {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes();
        assertThatThrownBy(() -> ImageMirrorService.sniffRasterImage(svg)).isInstanceOf(IllegalStateException.class);
        byte[] html = "<!DOCTYPE html><html><script>steal()</script></html>".getBytes();
        assertThatThrownBy(() -> ImageMirrorService.sniffRasterImage(html)).isInstanceOf(IllegalStateException.class);
    }
}
