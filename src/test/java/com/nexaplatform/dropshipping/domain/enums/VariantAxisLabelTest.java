package com.nexaplatform.dropshipping.domain.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El NOMBRE del eje de variación, en el idioma de quien mira.
 *
 * <p>Los valores ya se traducían; el nombre del eje se servía como estaba guardado, así que la ficha
 * en inglés enseñaba «Color / Talla».
 */
class VariantAxisLabelTest {

    @Test
    @DisplayName("los ejes conocidos se dicen en el idioma pedido")
    void losEjesConocidosSeTraducen() {
        assertThat(VariantAxisLabel.localize("Talla", "en")).isEqualTo("Size");
        assertThat(VariantAxisLabel.localize("Talla", "de")).isEqualTo("Größe");
        assertThat(VariantAxisLabel.localize("Color", "fr")).isEqualTo("Couleur");
        assertThat(VariantAxisLabel.localize("Color", "zh")).isEqualTo("颜色");
    }

    /** Mayúsculas y espacios los pone quien carga el producto, no quien lo mira. */
    @Test
    @DisplayName("el nombre guardado se reconoce sin importar mayúsculas ni espacios")
    void seReconoceSinImportarLaForma() {
        assertThat(VariantAxisLabel.localize("  TALLA ", "en")).isEqualTo("Size");
        assertThat(VariantAxisLabel.localize("tamaño", "en")).isEqualTo("Size");
    }

    /**
     * La cola de nombres libres se deja intacta: inventarle una traducción sería peor que dejarla en
     * el idioma en el que se cargó.
     */
    @Test
    @DisplayName("un eje que no está en la lista se sirve tal y como está guardado")
    void unEjeLibreSeQuedaComoEsta() {
        assertThat(VariantAxisLabel.localize("Talla de calcetín infantil", "en"))
                .isEqualTo("Talla de calcetín infantil");
        assertThat(VariantAxisLabel.localize("Medidas (largo x ancho en cm)", "de"))
                .isEqualTo("Medidas (largo x ancho en cm)");
    }

    @Test
    @DisplayName("sin idioma, o con uno que no servimos, se devuelve lo guardado")
    void sinIdiomaSeDevuelveLoGuardado() {
        assertThat(VariantAxisLabel.localize("Talla", null)).isEqualTo("Talla");
        assertThat(VariantAxisLabel.localize("Talla", "ja")).isEqualTo("Talla");
        assertThat(VariantAxisLabel.localize(null, "en")).isNull();
    }
}
