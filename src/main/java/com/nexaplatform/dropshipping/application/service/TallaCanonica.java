package com.nexaplatform.dropshipping.application.service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * La etiqueta de una talla, escrita igual siempre y en cada idioma.
 *
 * <p><b>Qué problema resuelve (25-sep-2026).</b> El chino de origen es UNIFORME —{@code 26码内长16.7},
 * {@code 27码内长17.3}, siempre el mismo término {@code 内长}— y aun así el traductor devolvía el mismo
 * concepto de treinta formas distintas por idioma: «Talla 28 largo 17,9 cm», «Talla 31: 19,8 cm»,
 * «Talla 34 plantilla 21 cm», «Größe # Einlegesohle # cm». Apiladas en la lista de tallas de una ficha
 * parecen datos distintos, y algunas eran directamente erróneas: {@code 内长} es el largo interior, no
 * la plantilla ni la entrepierna.
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

    /** Talla con largo interior: {@code 26码内长16.7cm}. El {@code 码} y la unidad pueden faltar. */
    private static final Pattern LARGO_INTERIOR = Pattern
            .compile("^(\\d+)码?内长(约)?([\\d.]+?)\\.?\\s*(?:cm|CM|厘米)?$");

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
    private static final Map<String, Double> A_KILOS = Map.of("斤", 0.5, "公斤", 1.0, "千克", 1.0, "kg", 1.0, "KG",
            1.0, "克", 0.001, "g", 0.001);

    /** Lote por rango de tallas: {@code 22-26一手拍5双}. */
    private static final Pattern LOTE_DE_PARES = Pattern.compile("^(\\d+)-(\\d+)一手拍(\\d+)双$");

    /** Las palabras de cada idioma: talla, largo interior, «aproximadamente» y pares. */
    private static final Map<String, String[]> PALABRAS = Map.of(
            "es", new String[] {"Talla", "largo interior", "aprox.", "pares"},
            "en", new String[] {"Size", "inner length", "approx.", "pairs"},
            "pt", new String[] {"Tamanho", "comprimento interno", "aprox.", "pares"},
            "fr", new String[] {"Taille", "longueur intérieure", "env.", "paires"},
            "de", new String[] {"Größe", "Innenlänge", "ca.", "Paare"},
            "it", new String[] {"Taglia", "lunghezza interna", "circa", "paia"},
            "nl", new String[] {"Maat", "binnenlengte", "ca.", "paar"});

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

        Matcher largo = LARGO_INTERIOR.matcher(zh);
        if (largo.matches()) {
            String medida = decimal(largo.group(3), idioma);
            String aprox = largo.group(2) != null ? p[2] + " " : "";
            return Optional.of(p[0] + " " + largo.group(1) + ", " + p[1] + " " + aprox + medida + " cm");
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

        Matcher lote = LOTE_DE_PARES.matcher(zh);
        if (lote.matches()) {
            return Optional.of(lote.group(1) + "-" + lote.group(2) + ", " + lote.group(3) + " " + p[3]);
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
     * La medida con el separador decimal del idioma y sin un separador colgando.
     *
     * <p>Lo de «sin separador colgando» no es teórico: el chino trae medidas como {@code 内长16.} y la
     * primera versión de esto las convirtió en «largo interior 16, cm», con la coma suelta delante de
     * la unidad. Se coló en 14 filas de preproducción.
     */
    private static String decimal(String medida, String idioma) {
        String limpia = medida.endsWith(".") ? medida.substring(0, medida.length() - 1) : medida;
        return "en".equalsIgnoreCase(idioma) ? limpia : limpia.replace('.', ',');
    }
}
