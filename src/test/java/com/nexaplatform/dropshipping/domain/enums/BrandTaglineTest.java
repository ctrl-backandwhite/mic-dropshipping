package com.nexaplatform.dropshipping.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El descriptor de la cabecera del correo.
 *
 * <p>Ahí ponía «Dropshipping», que describe cómo se cumple el pedido y no lo que se vende: a quien
 * acaba de comprar una camiseta no le dice qué tienda le escribe. El texto nuevo sale de lo que de
 * verdad hay en el catálogo —el 92 % es moda, calzado, bolsos y ropa interior— y va en el idioma de
 * quien lo lee, como el resto del correo.
 */
class BrandTaglineTest {

    @Test
    @DisplayName("dice lo que se vende, en el idioma de quien lee")
    void seDiceEnElIdiomaDeQuienLee() {
        assertThat(BrandTagline.of("es")).isEqualTo("Moda y complementos");
        assertThat(BrandTagline.of("en")).isEqualTo("Fashion and accessories");
        assertThat(BrandTagline.of("de")).isEqualTo("Mode und Accessoires");
        assertThat(BrandTagline.of("zh")).isEqualTo("时尚与配饰");
    }

    /** Un correo sin idioma no puede quedarse sin cabecera: sale en español, como el resto. */
    @Test
    @DisplayName("sin idioma, o con uno que no servimos, cae al español")
    void sinIdiomaCaeAlEspanol() {
        assertThat(BrandTagline.of(null)).isEqualTo("Moda y complementos");
        assertThat(BrandTagline.of("")).isEqualTo("Moda y complementos");
        assertThat(BrandTagline.of("ja")).isEqualTo("Moda y complementos");
    }

    @Test
    @DisplayName("da igual cómo venga escrito el código de idioma")
    void elCodigoSeNormaliza() {
        assertThat(BrandTagline.of(" EN ")).isEqualTo("Fashion and accessories");
    }

    /**
     * El socio de integración no es un comprador: contrata que nosotros enviemos por él, así que en su
     * correo la palabra describe el servicio. Distinguir por destinatario es justo lo que se decidió.
     */
    @Test
    @DisplayName("al socio de integración se le sigue hablando de dropshipping")
    void alSocioSeLeHablaDeDropshipping() {
        assertThat(BrandTagline.of(UserRole.PARTNER, "es")).isEqualTo("Dropshipping");
        assertThat(BrandTagline.of(UserRole.PARTNER, "en")).isEqualTo("Dropshipping");
        // En chino «dropshipping» no se lee: el término del sector es 一件代发.
        assertThat(BrandTagline.of(UserRole.PARTNER, "zh")).isEqualTo("一件代发");
    }

    @Test
    @DisplayName("cualquier otro papel ve lo que se vende")
    void elRestoVeLoQueSeVende() {
        assertThat(BrandTagline.of(UserRole.USER, "es")).isEqualTo("Moda y complementos");
        assertThat(BrandTagline.of(UserRole.ADMIN, "en")).isEqualTo("Fashion and accessories");
        assertThat(BrandTagline.of(UserRole.OPERATOR, "fr")).isEqualTo("Mode et accessoires");
        assertThat(BrandTagline.of(null, "it")).isEqualTo("Moda e accessori");
    }

    /** Ya no se le dice «dropshipping» a quien compra: eso es vocabulario de operador. */
    @Test
    @DisplayName("en ningún idioma se le llama dropshipping al comprador")
    void nuncaDiceDropshipping() {
        for (String lang : new String[] {"es", "en", "pt", "zh", "fr", "de", "it", "nl"}) {
            assertThat(BrandTagline.of(lang).toLowerCase()).doesNotContain("dropshipping");
        }
    }
}
