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
    @DisplayName("la talla por peso sale en KG y con el mismo texto en los siete idiomas")
    void tallaPorPeso() {
        // Formato pedido por el titular el 25-sep-2026. No lleva ni una palabra dentro, así que es el
        // MISMO en todos los idiomas: la letra de la talla y «KG» se entienden en los siete, y el
        // texto que había alrededor —«talla europea», «recomendado», «1 jin ≈ 0,5 kg»— no añadía nada
        // y llegaba de siete formas distintas.
        for (String idioma : new String[] {"es", "en", "pt", "fr", "de", "it", "nl"}) {
            assertThat(TallaCanonica.para("欧码XS(建议90-110斤）", idioma)).contains("XS - (45 - 55 KG)");
        }
    }

    @Test
    @DisplayName("el jin se convierte a kilos: son 500 gramos, no un kilo")
    void elJinSonMedioKilo() {
        // Dejar «90-110 jin» en la ficha es pedirle al comprador que convierta una unidad china para
        // saber si la prenda le vale. Y tomarlo por kilos doblaría el peso recomendado.
        assertThat(TallaCanonica.para("欧码XXL(建议180-200斤)", "es")).contains("XXL - (90 - 100 KG)");
        assertThat(TallaCanonica.para("2XL【建议150-170斤】", "es")).contains("2XL - (75 - 85 KG)");
    }

    @Test
    @DisplayName("los kilos y los gramos del proveedor también acaban en KG")
    void kilosYGramos() {
        assertThat(TallaCanonica.para("L【50-60公斤】", "es")).contains("L - (50 - 60 KG)");
        assertThat(TallaCanonica.para("M(45000-55000克)", "es")).contains("M - (45 - 55 KG)");
    }

    @Test
    @DisplayName("medio kilo se conserva; un kilo redondo no arrastra decimales")
    void medioKiloSeConserva() {
        // 105 jin son 52,5 kg y ese medio kilo es información real. Lo que no se quiere es «50,0 KG».
        assertThat(TallaCanonica.para("S(建议95-105斤)", "es")).contains("S - (47,5 - 52,5 KG)");
        assertThat(TallaCanonica.para("S(建议100-120斤)", "es")).contains("S - (50 - 60 KG)");
    }

    @Test
    @DisplayName("las siete formas de escribirlo dan el mismo resultado")
    void lasSieteFormasConvergen() {
        // El proveedor escribe lo mismo con corchetes, con paréntesis, con «建议» o sin nada. En el
        // catálogo hay siete variantes y todas significan lo mismo.
        assertThat(TallaCanonica.para("欧码L(建议140-160斤)", "es")).contains("L - (70 - 80 KG)");
        assertThat(TallaCanonica.para("L【建议140-160斤】", "es")).contains("L - (70 - 80 KG)");
        assertThat(TallaCanonica.para("L【140-160斤】", "es")).contains("L - (70 - 80 KG)");
        assertThat(TallaCanonica.para("L 140-160斤", "es")).contains("L - (70 - 80 KG)");
        assertThat(TallaCanonica.para("L建议140-160斤", "es")).contains("L - (70 - 80 KG)");
    }

    @Test
    @DisplayName("las formas raras del proveedor también convergen")
    void formasRaras() {
        // Talla repetida dentro del paréntesis, «推荐» en vez de «建议», la unidad puesta en los dos
        // números y un «内» («dentro de») al final. Todas dicen lo mismo.
        assertThat(TallaCanonica.para("3XL(3XL【建议145-160斤】)", "es")).contains("3XL - (72,5 - 80 KG)");
        assertThat(TallaCanonica.para("L(推荐112-128斤）", "es")).contains("L - (56 - 64 KG)");
        assertThat(TallaCanonica.para("XL 【建议135斤~150斤】", "es")).contains("XL - (67,5 - 75 KG)");
    }

    /**
     * EL control del formato nuevo, y el que más importa: una talla con código compuesto NO se
     * simplifica.
     *
     * <p>El «34/75ABC» es la talla de sujetador. Dejar una «M» más limpia a costa de perderla sería
     * quitarle al comprador justo el dato con el que decide, y en lencería es el que manda.
     */
    @Test
    @DisplayName("una talla con código de sujetador no se simplifica")
    void codigoCompuestoNoSeToca() {
        assertThat(TallaCanonica.para("M（34/75ABC）建议90-105斤", "es")).isEmpty();
        assertThat(TallaCanonica.para("58/195/4XL适合200-220斤", "es")).isEmpty();
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
