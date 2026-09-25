package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.TextoTraducido;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cómo se escribe un texto traducido en su idioma.
 *
 * <p>Regla del titular (25-sep-2026): las traducciones tienen que ser correctas también en la forma.
 * En la misma lista de tallas de una ficha convivían «16,7 cm», «17.3 cm», «17,9cm» y «talla 31: 19.8
 * cm»: cada valor llega del traductor por su cuenta y nadie los ve juntos hasta que están apilados en
 * pantalla. Medido en preproducción: 2.244 valores por idioma con punto decimal donde tocaba coma y
 * 1.900 con la unidad pegada al número.
 *
 * <p>Se corrige lo DETERMINISTA. La redacción no: cambiar «largo interior» por «largo» sería traducir.
 */
class TextoTraducidoTest {

    @Test
    @DisplayName("el separador decimal es el del idioma")
    void separadorDecimalPorIdioma() {
        assertThat(TextoTraducido.normaliza("Talla 27 largo 16.5 cm", "es")).isEqualTo("Talla 27 largo 16,5 cm");
        assertThat(TextoTraducido.normaliza("Maat 27 lengte 16.5 cm", "nl")).isEqualTo("Maat 27 lengte 16,5 cm");
        // El inglés va al revés: la coma decimal es la que está mal ahí.
        assertThat(TextoTraducido.normaliza("Size 27 length 16,5 cm", "en")).isEqualTo("Size 27 length 16.5 cm");
    }

    @Test
    @DisplayName("la unidad se separa del número")
    void laUnidadSeSepara() {
        assertThat(TextoTraducido.normaliza("Talla 29 largo 18cm", "es")).isEqualTo("Talla 29 largo 18 cm");
        assertThat(TextoTraducido.normaliza("Peso 500g", "es")).isEqualTo("Peso 500 g");
    }

    @Test
    @DisplayName("la primera letra en mayúscula, y solo la primera")
    void mayusculaSoloLaPrimera() {
        assertThat(TextoTraducido.normaliza("talla 26, largo interior 16 cm", "es"))
                .isEqualTo("Talla 26, largo interior 16 cm");
        // EL control del «Title Case»: en español los colores van en minúscula, y ponerlos en
        // mayúscula uno a uno sería una falta, no una mejora.
        assertThat(TextoTraducido.normaliza("beige caqui", "es")).isEqualTo("Beige caqui");
        assertThat(TextoTraducido.normaliza("W1999 wit en groen", "nl")).isEqualTo("W1999 wit en groen");
    }

    @Test
    @DisplayName("un rango o un código no son un decimal y no se tocan")
    void rangosYCodigosIntactos() {
        // «31-36» es un rango de tallas y «v1.2.3» una versión: cambiarles el punto sería estropearlos.
        //
        // Este caso salió en rojo con la primera versión del patrón, y tenía razón: convertía «v1.2.3»
        // en «v1,2.3», a medias, porque el primer dígito se consumía y el segundo par ya no casaba.
        // Una cadena con MÁS de un separador no es un decimal; se deja entera.
        assertThat(TextoTraducido.normaliza("31-36 lote de 6", "es")).isEqualTo("31-36 lote de 6");
        assertThat(TextoTraducido.normaliza("Modelo v1.2.3", "es")).isEqualTo("Modelo v1.2.3");
        assertThat(TextoTraducido.normaliza("Tallas 26-30, 5 pares", "es")).isEqualTo("Tallas 26-30, 5 pares");
    }

    @Test
    @DisplayName("los espacios de más se recortan")
    void espaciosDeMas() {
        assertThat(TextoTraducido.normaliza("  Talla   26  ", "es")).isEqualTo("Talla 26");
    }

    @Test
    @DisplayName("nulo y vacío no revientan")
    void nuloYVacio() {
        assertThat(TextoTraducido.normaliza(null, "es")).isNull();
        assertThat(TextoTraducido.normaliza("   ", "es")).isEmpty();
    }
}
