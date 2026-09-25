package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.TallaCanonica;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La etiqueta de una talla, escrita igual siempre.
 *
 * <p>Regla del titular (25-sep-2026): las traducciones tienen que ser correctas también en lo
 * semántico. El chino de origen dice lo mismo de tres maneras
 * —{@code 15内长12.3}, {@code 27码：17cm}, {@code 32码：内长19.6cm}— y el traductor multiplicaba esa
 * variedad hasta 36 formas por idioma, algunas equivocadas: «plantilla» y
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
        assertThat(TallaCanonica.para("26码内长16.7cm", "es")).contains("Talla 26 : largo interior 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "en")).contains("Size 26 : inner length 16.7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "de")).contains("Größe 26 : Innenlänge 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "fr")).contains("Taille 26 : longueur intérieure 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "it")).contains("Taglia 26 : lunghezza interna 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "nl")).contains("Maat 26 : binnenlengte 16,7 cm");
        assertThat(TallaCanonica.para("26码内长16.7cm", "pt")).contains("Tamanho 26 : comprimento interno 16,7 cm");
    }

    @Test
    @DisplayName("el «aproximadamente» del chino se conserva")
    void conservaElAproximadamente() {
        // 约 significa «aproximadamente» y es un dato, no ruido: una medida aproximada no se puede
        // presentar como exacta a quien compra calzado por internet.
        assertThat(TallaCanonica.para("34码内长约21.5CM", "es")).contains("Talla 34 : largo interior aprox. 21,5 cm");
        assertThat(TallaCanonica.para("34码内长约21.5CM", "de")).contains("Größe 34 : Innenlänge ca. 21,5 cm");
    }

    @Test
    @DisplayName("el 码 y la unidad pueden faltar, y el resultado es el mismo")
    void elCodigoYLaUnidadSonOpcionales() {
        assertThat(TallaCanonica.para("30内长19", "es")).contains("Talla 30 : largo interior 19 cm");
        assertThat(TallaCanonica.para("30码内长19厘米", "es")).contains("Talla 30 : largo interior 19 cm");
    }

    @Test
    @DisplayName("un separador colgando no deja la coma suelta delante de la unidad")
    void separadorColgando() {
        // El chino trae medidas como «内长16.» y «内长20,» y la primera versión las convirtió en
        // «16, cm», con la coma suelta. Se coló en 14 filas de preproducción antes de verse.
        assertThat(TallaCanonica.para("28码内长16.", "es")).contains("Talla 28 : largo interior 16 cm");
        assertThat(TallaCanonica.para("32码内长20,", "es")).contains("Talla 32 : largo interior 20 cm");
        assertThat(TallaCanonica.para("31码内长19.5,", "es")).contains("Talla 31 : largo interior 19,5 cm");
        // Y también colgando por delante: «,29码内长18.5,» llega así de 1688.
        assertThat(TallaCanonica.para(",29码内长18.5,", "es")).contains("Talla 29 : largo interior 18,5 cm");
    }

    /**
     * Un cero final no es precisión: se quita.
     *
     * <p>El proveedor escribe {@code 24码：15.0cm}, y publicar «15,0 cm» promete al comprador una
     * medida al milímetro que su ficha no da. El cero solo se conserva cuando ES el número.
     */
    @Test
    @DisplayName("el cero decimal que sobra no se publica")
    void elCeroQueSobra() {
        assertThat(TallaCanonica.para("24码：15.0cm", "es")).contains("Talla 24 : 15 cm");
        assertThat(TallaCanonica.para("26码内长约16.0CM", "es")).contains("Talla 26 : largo interior aprox. 16 cm");
        assertThat(TallaCanonica.para("30码：20cm", "es")).contains("Talla 30 : 20 cm");
        assertThat(TallaCanonica.para("28码：17.5cm", "es")).contains("Talla 28 : 17,5 cm");
    }

    @Test
    @DisplayName("la talla por peso sale en KG y con el mismo texto en los siete idiomas")
    void tallaPorPeso() {
        // Formato pedido por el titular el 25-sep-2026. No lleva ni una palabra dentro, así que es el
        // MISMO en todos los idiomas: la letra de la talla y «KG» se entienden en los siete, y el
        // texto que había alrededor —«talla europea», «recomendado», «1 jin ≈ 0,5 kg»— no añadía nada
        // y llegaba de siete formas distintas.
        for (String idioma : new String[]{"es", "en", "pt", "fr", "de", "it", "nl"}) {
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

    /**
     * Un lote al por mayor NO es una talla, y por eso ya no se traduce: se descarta.
     *
     * <p>«27-31码一手拍5双» son cinco pares surtidos del 27 al 31, una condición de compra. Puesto en el
     * selector de talla junto al 27, al 28 y al 29, el comprador elige «tallas 27-31, lote de 5 pares»
     * creyendo que compra un par. En preproducción había 651 valores así, en 285 productos.
     */
    @Test
    @DisplayName("un lote de pares se reconoce para descartarlo, en las formas en que lo escribe el proveedor")
    void elLoteSeDescarta() {
        assertThat(TallaCanonica.esLoteDePares("22-26一手拍5双")).isTrue();
        assertThat(TallaCanonica.esLoteDePares("27-31码一手拍5双")).isTrue();
        assertThat(TallaCanonica.esLoteDePares("31-36码一手拍6双")).isTrue();
        // Y ya no se le busca traducción, ni siquiera cuando el rango parece una talla.
        assertThat(TallaCanonica.para("22-26一手拍5双", "es")).isEmpty();
    }

    /**
     * El control del descarte: una talla de verdad NO se confunde con un lote.
     *
     * <p>Sin esto, un filtro demasiado ancho vaciaría el selector de tallas del producto entero.
     */
    @Test
    @DisplayName("una talla normal no se toma por un lote")
    void laTallaNormalNoEsUnLote() {
        assertThat(TallaCanonica.esLoteDePares("26码内长16.7cm")).isFalse();
        assertThat(TallaCanonica.esLoteDePares("27码：17cm")).isFalse();
        assertThat(TallaCanonica.esLoteDePares("黑色")).isFalse();
        assertThat(TallaCanonica.esLoteDePares(null)).isFalse();
    }

    /**
     * El proveedor escribe la misma frase de tres maneras y las tres salen iguales.
     *
     * <p>Formato fijado por el titular el 25-sep-2026: el separador es DOS PUNTOS, no una coma, porque
     * lo que va detrás es una aclaración de la talla y no otro dato de la misma lista.
     */
    @Test
    @DisplayName("las formas chinas de la talla con medida convergen en el mismo texto")
    void lasFormasDeLaMedida() {
        assertThat(TallaCanonica.para("15内长12.3", "es")).contains("Talla 15 : largo interior 12,3 cm");
        assertThat(TallaCanonica.para("15码内长12.3cm", "es")).contains("Talla 15 : largo interior 12,3 cm");
        assertThat(TallaCanonica.para("15码：内长12.3cm", "es")).contains("Talla 15 : largo interior 12,3 cm");
        assertThat(TallaCanonica.para("15码/内长12.3cm", "es")).contains("Talla 15 : largo interior 12,3 cm");
        assertThat(TallaCanonica.para("15码/鞋内长12.3厘米", "es")).contains("Talla 15 : largo interior 12,3 cm");
        assertThat(TallaCanonica.para("15码鞋内长约/12.3厘米", "es")).contains("Talla 15 : largo interior aprox. 12,3 cm");
        assertThat(TallaCanonica.para("15码子12.3厘米", "es")).contains("Talla 15 : 12,3 cm");
        assertThat(TallaCanonica.para("15鞋内长12.3厘米", "es")).contains("Talla 15 : largo interior 12,3 cm");
        assertThat(TallaCanonica.para("15-内长12.3cm", "es")).contains("Talla 15 : largo interior 12,3 cm");
        assertThat(TallaCanonica.para("15码/内长12.3厘", "es")).contains("Talla 15 : largo interior 12,3 cm");
        // Repetido: «27码内长内长16.6cm» existe en el catálogo y dice lo mismo una vez.
        assertThat(TallaCanonica.para("15码内长内长12.3cm", "es")).contains("Talla 15 : largo interior 12,3 cm");
    }

    /**
     * El proveedor TRUNCA la unidad, y eso no la convierte en otra unidad.
     *
     * <p>{@code 28码内长约17.0c} y {@code 23码：15.1m} salen de fichas cuyas demás tallas vienen en
     * centímetros. Publicar «15,1 m» sería dar por buena una errata: un pie de quince metros no existe.
     */
    @Test
    @DisplayName("la unidad truncada del proveedor sigue siendo centímetros")
    void unidadTruncada() {
        assertThat(TallaCanonica.para("28码内长约17.0c", "es")).contains("Talla 28 : largo interior aprox. 17 cm");
        assertThat(TallaCanonica.para("23码：15.1m", "es")).contains("Talla 23 : 15,1 cm");
        assertThat(TallaCanonica.para("22码：14.2cm，", "es")).contains("Talla 22 : 14,2 cm");
    }

    /**
     * Cuando el chino NO dice 内长, no se escribe «largo interior».
     *
     * <p>«27码：17cm» da la medida sin decir de qué es. Es casi seguro el largo interior, pero
     * afirmarlo por el proveedor sería añadir a su ficha un dato que no da.
     */
    @Test
    @DisplayName("sin 内长 en el chino no se nombra el largo interior")
    void sinConceptoNoSeInventa() {
        assertThat(TallaCanonica.para("27码：17cm", "es")).contains("Talla 27 : 17 cm");
        assertThat(TallaCanonica.para("28码：17.5cm", "es")).contains("Talla 28 : 17,5 cm");
        assertThat(TallaCanonica.para("28码：17.5cm", "en")).contains("Size 28 : 17.5 cm");
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
        // Un número suelto NO se parte en talla y medida: sin 码, dos puntos ni 内长 que los separen,
        // «27» daría «Talla 2 : 7 cm». Esa es la trampa que evita la mirada inicial del patrón.
        assertThat(TallaCanonica.para("27", "es")).isEmpty();
        assertThat(TallaCanonica.para("1000码", "es")).isEmpty();
        // «41男码» es «talla 41 de hombre»: no trae medida, así que no hay nada canónico que escribir
        // y el «de hombre» lo tiene que traducir el traductor. Inventar «Talla 41 : 41 cm» sería peor.
        assertThat(TallaCanonica.para("41男码", "es")).isEmpty();
        assertThat(TallaCanonica.para("", "es")).isEmpty();
        assertThat(TallaCanonica.para(null, "es")).isEmpty();
        // Un idioma que no está en la tabla tampoco se inventa.
        assertThat(TallaCanonica.para("26码内长16.7cm", "ru")).isEmpty();
    }
}
