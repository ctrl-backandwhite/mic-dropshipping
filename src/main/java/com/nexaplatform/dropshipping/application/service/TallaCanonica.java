package com.nexaplatform.dropshipping.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La etiqueta de una talla, escrita igual siempre y en cada idioma.
 *
 * <p><b>Qué problema resuelve (25-sep-2026).</b> El chino de origen dice lo mismo de tres maneras
 * —{@code 15内长12.3}, {@code 27码：17cm}, {@code 32码：内长19.6cm}— y el traductor multiplicaba esa
 * variedad: «Talla 28 largo 17,9 cm», «Talla 31: 19,8 cm», «Talla 34 plantilla 21 cm», «Größe #
 * Einlegesohle # cm». Apiladas en la lista de tallas de una ficha parecen datos distintos, y algunas
 * eran directamente erróneas: {@code 内长} es el largo interior, no la plantilla ni la entrepierna.
 *
 * <p>No es un maquillaje: se traduce DESDE EL CHINO, que es la fuente, y por eso puede corregir lo que
 * el traductor se inventó. Lo que no encaja en un patrón conocido se deja como venga —ahí el traductor
 * sí es la única fuente— y solo pasa por {@link TextoTraducido}.
 *
 * <p>Las palabras de cada idioma no salen de la cabeza de nadie: son las que ya usaba el catálogo de
 * forma mayoritaria para ese mismo patrón chino, medidas sobre 2.542 valores en preproducción.
 */
public final class TallaCanonica {

    private TallaCanonica() {
    }

    /**
     * Talla con su medida, en las formas que usa el proveedor: {@code 15内长12.3}, {@code 27码：17cm},
     * {@code 32码：内长19.6cm}, {@code 31码/内长19cm}, {@code 32码/鞋内长19.5厘米},
     * {@code 29码鞋内长约/18.0厘米}, {@code 21码子13.5厘米}, {@code 26鞋内长16厘米} y
     * {@code 24-内长15.0cm}. Son quince maneras de escribir el mismo dato.
     *
     * <p>El {@code 码} («talla»), los dos puntos —chinos o latinos—, el {@code 内长} («largo interior»),
     * el {@code 约} («aproximadamente») y la unidad son todos opcionales, pero <b>alguno</b> tiene que
     * separar el número de talla de la medida: eso es lo que exige la mirada inicial. Sin ella un valor
     * como {@code 27} casaría partiéndose en talla «2» y medida «7».
     *
     * <p>El {@code 鞋} es «zapato» ({@code 鞋内长}, «largo interior del zapato»): el mismo dato con una
     * palabra más. El {@code [.,，]?} está para el chino malformado tipo {@code 26码内长16.},
     * {@code 32码内长20,} y {@code 22码：14.2cm，}, que llegan con el separador colgando: se lo come el
     * patrón en vez de arrastrarlo al texto traducido.
     *
     * <p>La unidad admite {@code c}, {@code C}, {@code m} y {@code 厘} sueltas porque el proveedor
     * TRUNCA el «cm» y el «厘米» ({@code 28码内长约17.0c}, {@code 23码：15.1m}, {@code 29码/内长17.8厘}).
     * No son unidades distintas: un pie de 15 metros no existe y las tallas vecinas de esas mismas
     * fichas vienen en centímetros.
     */
    private static final Pattern TALLA_CON_MEDIDA = Pattern.compile("^[,，\\s]*(?=\\d+(?:码|[：:/-]|内长|鞋))(\\d{1,3})"
            + "码?子?[：:/-]?\\s*鞋?(内长)?(?:内长)*(约)?[/：:]?\\s*(\\d{1,3}(?:[.,]\\d+)?)[.,，]?\\s*"
            + "(?:厘米|厘|cm|CM|[cCm])?[，,]?\\s*$");

    /**
     * Talla por letra con el peso recomendado: {@code 欧码XS(建议90-110斤)}, {@code 2XL【150-170斤】}.
     *
     * <p>Todo es opcional menos la talla y el rango: el prefijo {@code 欧码} («talla europea»), los
     * corchetes o paréntesis, el {@code 建议} («recomendado») y la unidad. En el catálogo aparecen
     * siete formas distintas de escribir lo mismo.
     */
    private static final Pattern TALLA_POR_PESO = Pattern.compile("^(?:欧码)?\\s*([2-9]?X{0,3}[SMLsml])"
            // Entre la talla y el rango el proveedor mete de todo: paréntesis, corchetes, la talla
            // repetida («3XL(3XL【…»), y «建议», «推荐» o «适合», que significan lo mismo.
            + "(?:[\\s(（【\\[]|建议|推荐|适合|[2-9]?X{0,3}[SMLsml])*"
            + "(\\d+(?:\\.\\d+)?)\\s*(?:斤|公斤|千克|kg|KG|克|g)?\\s*[-~～至]\\s*"
            + "(\\d+(?:\\.\\d+)?)\\s*(斤|公斤|千克|kg|KG|克|g)[内以]?\\s*[】)\\]）]*\\s*$");

    /**
     * Un código de talla compuesto, como {@code M（34/75ABC）建议90-105斤}.
     *
     * <p>Esos NO se simplifican: el «34/75ABC» es la talla de sujetador y perderla para dejar una «M»
     * más limpia sería quitarle al comprador el dato que de verdad necesita. Se quedan con la
     * traducción que tengan.
     */
    private static final Pattern CODIGO_COMPUESTO = Pattern.compile("^[^0-9]{0,4}[（(\\[【]?[^）)\\]】]*/");

    /** A cuántos kilos equivale cada unidad que usa el proveedor. */
    private static final Map<String, Double> A_KILOS = Map.of("斤", 0.5, "公斤", 1.0, "千克", 1.0, "kg", 1.0, "KG", 1.0, "克",
            0.001, "g", 0.001);

    /**
     * Un lote de pares al por mayor: {@code 一手拍5双} («un lote de 5 pares»), normalmente precedido de
     * un rango de tallas, {@code 27-31码一手拍5双}.
     *
     * <p>Se busca en cualquier posición, no de principio a fin, porque el proveedor lo escribe de al
     * menos dieciséis formas: con {@code 码} y sin él, con el rango delante o detrás, y a veces con
     * texto suelto alrededor.
     */
    private static final Pattern LOTE_DE_PARES = Pattern.compile("一手拍\\d+双");

    /** Las palabras de cada idioma: talla, largo interior y «aproximadamente». */
    private static final Map<String, String[]> PALABRAS = Map.of("es",
            new String[]{"Talla", "largo interior", "aprox."}, "en", new String[]{"Size", "inner length", "approx."},
            "pt", new String[]{"Tamanho", "comprimento interno", "aprox."}, "fr",
            new String[]{"Taille", "longueur intérieure", "env."}, "de", new String[]{"Größe", "Innenlänge", "ca."},
            "it", new String[]{"Taglia", "lunghezza interna", "circa"}, "nl",
            new String[]{"Maat", "binnenlengte", "ca."});

    /**
     * Si ese original chino es un lote de pares al por mayor, y por tanto NO es una talla.
     *
     * <p><b>Por qué se descarta en vez de traducirse (25-sep-2026).</b> «27-31码一手拍5双» es una
     * condición de compra —cinco pares surtidos del 27 al 31—, no un número de calzado. Puesto en el
     * selector de talla junto al 27, al 28 y al 29, el comprador elige «tallas 27-31, lote de 5 pares»
     * creyendo que compra un par. Antes se traducía; ahora el valor no llega a existir.
     */
    public static boolean esLoteDePares(String valorZh) {
        return valorZh != null && LOTE_DE_PARES.matcher(valorZh).find();
    }

    /**
     * La etiqueta canónica para ese original chino en ese idioma, si el original es un patrón conocido.
     *
     * <p>Vacío significa «no sé escribir esto mejor que el traductor»: el llamante se queda con lo que
     * le llegó. Nunca devuelve una frase a medias.
     */
    public static Optional<String> para(String valorZh, String idioma) {
        if (valorZh == null || idioma == null) {
            return Optional.empty();
        }
        String[] p = PALABRAS.get(idioma.toLowerCase());
        if (p == null) {
            return Optional.empty();
        }
        String zh = valorZh.trim();

        // TALLA CON MEDIDA, con el formato que fijó el titular el 25-sep-2026: «Talla 15 : largo
        // interior 12,3 cm». El separador es DOS PUNTOS y no una coma porque lo que va detrás es una
        // aclaración de la talla, no otro dato de la misma lista; con coma se leían como dos columnas.
        //
        // «largo interior» se escribe SOLO cuando el chino trae 内长. Cuando el proveedor se limita a
        // «27码：17cm» queda «Talla 27 : 17 cm»: la medida es la misma cosa, pero afirmarlo por él
        // sería añadir un dato que su ficha no da.
        Matcher medida = TALLA_CON_MEDIDA.matcher(zh);
        if (medida.matches()) {
            String concepto = medida.group(2) != null ? p[1] + " " : "";
            String aprox = medida.group(3) != null ? p[2] + " " : "";
            return Optional.of(
                    p[0] + " " + medida.group(1) + " : " + concepto + aprox + decimal(medida.group(4), idioma) + " cm");
        }

        // TALLA POR PESO, con el formato que pidió el titular el 25-sep-2026: «XS - (45 - 55 KG)».
        //
        // Sin una palabra dentro, así que sale IGUAL en los siete idiomas. Es deliberado: la letra de
        // la talla y «KG» se entienden en todos, y el texto que había alrededor —«talla europea»,
        // «recomendado», «1 jin ≈ 0,5 kg»— no añadía nada y llegaba de siete formas distintas.
        //
        // Y todo en kilos: el proveedor mezcla jin, gramos y kilos, y un 斤 son 500 g. Dejar «90-110
        // jin» en la ficha es pedirle al comprador que convierta una unidad china para saber si la
        // prenda le vale.
        Matcher peso = TALLA_POR_PESO.matcher(zh);
        if (peso.matches() && !CODIGO_COMPUESTO.matcher(zh).find()) {
            Double factor = A_KILOS.get(peso.group(4));
            if (factor != null) {
                return Optional.of(peso.group(1).toUpperCase() + " - (" + kilos(peso.group(2), factor) + " - "
                        + kilos(peso.group(3), factor) + " KG)");
            }
        }
        return Optional.empty();
    }

    /**
     * El peso en kilos, sin decimales cuando no hacen falta.
     *
     * <p>Un jin impar da medio kilo —105 jin son 52,5 kg— y ese medio kilo es información real; lo que
     * no se quiere es un «50,0 KG» donde basta «50».
     */
    private static String kilos(String cantidad, double factor) {
        double kg = Double.parseDouble(cantidad) * factor;
        if (kg == Math.rint(kg)) {
            return String.valueOf((long) kg);
        }
        return String.valueOf(kg).replace('.', ',');
    }

    /**
     * La medida con el separador decimal del idioma —coma en todos menos en inglés— y sin ceros que
     * sobren.
     *
     * <p>El proveedor escribe {@code 24码：15.0cm}, y publicar «15,0 cm» promete una precisión de
     * milímetro que su ficha no da. «15 cm» dice exactamente lo que él dijo. El cero solo se quita
     * cuando hay parte decimal: en «20» el cero es el número.
     */
    private static String decimal(String medida, String idioma) {
        String limpia = medida.replace(',', '.');
        if (limpia.indexOf('.') >= 0) {
            limpia = limpia.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return "en".equalsIgnoreCase(idioma) ? limpia : limpia.replace('.', ',');
    }
}
