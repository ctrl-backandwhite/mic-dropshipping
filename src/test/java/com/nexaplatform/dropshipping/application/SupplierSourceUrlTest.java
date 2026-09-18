package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ErrorCode;
import com.nexaplatform.dropshipping.application.service.SupplierSourceUrl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * El enlace de origen que el admin corrige a mano sólo puede apuntar al mercado de origen.
 *
 * <p>Ese enlace es el que se abre para COMPRAR la mercancía al proveedor: si aceptara cualquier
 * dirección, el botón «Comprar en origen» llevaría a donde quisiera quien pegó el texto, y un
 * {@code javascript:} guardado ahí se ejecutaría en el navegador del propio admin.
 */
@DisplayName("El enlace de origen sólo se acepta si es de 1688 o Alibaba por http(s)")
class SupplierSourceUrlTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "https://detail.1688.com/offer/123456789.html",
        "http://detail.1688.com/offer/123456789.html",
        "https://m.1688.com/offer/123456789.html",
        "https://1688.com/offer/1.html",
        "https://www.alibaba.com/product-detail/foo_123.html",
        "https://spanish.alibaba.com/product-detail/foo_123.html",
    })
    void seAceptaLaFichaDelMercadoDeOrigen(String url) {
        assertThatCode(() -> SupplierSourceUrl.requireValid(url)).doesNotThrowAnyException();
    }

    @Test
    void seDevuelveElEnlaceSinEspaciosSobrantes() {
        // El admin pega el enlace desde el navegador y suele arrastrar espacios o un salto de línea.
        assertThat(SupplierSourceUrl.requireValid("  https://detail.1688.com/offer/9.html \n"))
                .isEqualTo("https://detail.1688.com/offer/9.html");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "https://www.amazon.es/dp/B000",              // otro marketplace
        "https://1688.com.attacker.net/offer/1.html", // el dominio permitido como PREFIJO del malicioso
        "https://mi1688.com/offer/1.html",            // el dominio permitido como SUFIJO sin punto
        "https://alibaba.com.evil.org/x",
    })
    void seRechazaCualquierDominioQueNoSeaElDelProveedor(String url) {
        assertThatThrownBy(() -> SupplierSourceUrl.requireValid(url))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PRODUCT_SOURCE_URL_INVALID.name());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "javascript:alert(1)",                 // se ejecutaría al pulsar «Comprar en origen»
        "data:text/html,<script>x</script>",
        "file:///etc/passwd",
        "ftp://detail.1688.com/offer/1.html",
        "detail.1688.com/offer/1.html",        // sin esquema no hay host que comprobar
        "no es una url",
    })
    void seRechazaTodoLoQueNoSeaHttpOHttps(String url) {
        assertThatThrownBy(() -> SupplierSourceUrl.requireValid(url))
                .isInstanceOf(BusinessException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n"})
    void seRechazaElEnlaceVacio(String url) {
        assertThatThrownBy(() -> SupplierSourceUrl.requireValid(url))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getCode())
                .isEqualTo(ErrorCode.PRODUCT_SOURCE_URL_INVALID.name());
    }

    @Test
    void seRechazaUnEnlaceMasLargoQueLaColumna() {
        // 800 caracteres es el tope de product.source_url: por encima, la base de datos lo truncaría y el
        // admin se quedaría con un enlace roto creyendo que se guardó entero.
        String tooLong = "https://detail.1688.com/offer/" + "9".repeat(800) + ".html";
        assertThatThrownBy(() -> SupplierSourceUrl.requireValid(tooLong))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void elErrorEstaTraducidoALosOchoIdiomas() {
        // Si faltara un idioma el usuario vería el mensaje genérico en vez del motivo real.
        for (String lang : new String[] {"es", "en", "pt", "zh", "fr", "de", "it", "nl"}) {
            assertThat(ErrorCode.PRODUCT_SOURCE_URL_INVALID.of(lang)).isNotBlank();
        }
        assertThat(ErrorCode.PRODUCT_SOURCE_URL_INVALID.of("en"))
                .isNotEqualTo(ErrorCode.PRODUCT_SOURCE_URL_INVALID.of("es"));
    }
}
