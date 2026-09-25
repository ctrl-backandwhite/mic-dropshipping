package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.TallaCanonica;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La etiqueta de una talla, escrita igual siempre.
 *
 * <p>Regla del titular (25-sep-2026): las traducciones tienen que ser correctas también en lo
 * semántico. El chino de origen es UNIFORME —siempre {@code 内长}, «largo interior»— y el traductor
 * devolvía el mismo concepto de hasta 36 formas por idioma, algunas equivocadas: «plantilla» y
 * «entrepierna» no son el largo interior. Apiladas en la lista de tallas de una ficha parecían datos
 * distintos.
 *
 * <p>Se traduce DESDE EL CHINO, que es la fuente; por eso esto puede corregir lo que el traductor se
 * inventó, y no al revés.
 */
class TallaCanonicaTest {

    @Test
    @DisplayName("una talla con su largo interior se escribe igual en los siete idiomas")
    void tallaConLargoInterior() {
        assertThat(TallaCanonica.para("26码内长16.7cm", "es")).contains("Talla 26, largo interior 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "en")).contains("Size 26, inner length 16.7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "de")).contains("Größe 26, Innenlänge 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "fr")).contains("Taille 26, longueur intérieure 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "it")).contains("Taglia 26, lunghezza interna 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "nl")).contains("Maat 26, binnenlengte 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "pt")).contains("Tamanho 26, comprimento interno 16,7 cm");
    }

    @Test
    @DisplayName("el «aproximadamente» del chino se conserva")
    void conservaElAproximadamente() {
        // 约 significa «aproximadamente» y es un dato, no ruido: una medida aproximada no se puede
        // presentar como exacta a quien compra calzado por internet.
        assertThat(TallaCanonica.para("34码内长约21.5CM", "es")).contains("Talla 34, largo interior aprox. 21,5 cm");
        assertThat(TallaCanonica.para("34码内长约21.5CM", "de")).contains("Größe 34, Innenlänge ca. 21,5 cm");
    }

    @Test
    @DisplayName("el 码 y la unidad pueden faltar, y el resultado es el mismo")
    void elCodigoYLaUnidadSonOpcionales() {
        assertThat(TallaCanonica.para("30内长19", "es")).contains("Talla 30, largo interior 19 cm");
        assertThat(TallaCanonica.para("30码内长19厘米", "es")).contains("Talla 30, largo interior 19 cm");
    }

    @Test
    @DisplayName("un separador colgando no deja la coma suelta delante de la unidad")
    void separadorColgando() {
        // El chino trae medidas como «内长16.» y la primera versión las convirtió en «16, cm», con la
        // coma suelta. Se coló en 14 filas de preproducción antes de verse.
        assertThat(TallaCanonica.para("28码内长16.", "es")).contains("Talla 28, largo interior 16 cm");
    }

    @Test
    @DisplayName("un lote por rango de tallas")
    void loteDePares() {
        assertThat(TallaCanonica.para("22-26一手拍5双", "es")).contains("22-26, 5 pares");
        assertThat(TallaCanonica.para("22-26一手拍5双", "nl")).contains("22-26, 5 paar");
    }

    /**
     * EL control, y el que sostiene todo lo demás: lo que no se reconoce NO se toca.
     *
     * <p>Si esto devolviera algo para cualquier entrada, el canonicalizador dejaría de ser una
     * corrección y pasaría a ser una máquina de inventar etiquetas sobre texto que no entiende.
     */
    @Test
    @DisplayName("lo que no es un patrón conocido se deja al traductor")
    void loDesconocidoSeDejaEnPaz() {
        assertThat(TallaCanonica.para("黑色", "es")).isEmpty();
        assertThat(TallaCanonica.para("均码", "es")).isEmpty();
        assertThat(TallaCanonica.para("XL", "es")).isEmpty();
        assertThat(TallaCanonica.para("", "es")).isEmpty();
        assertThat(TallaCanonica.para(null, "es")).isEmpty();
        // Un idioma que no está en la tabla tampoco se inventa.
        assertThat(TallaCanonica.para("26码内长16.7cm", "ru")).isEmpty();
    }
}
