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

        Matcher lote = LOTE_DE_PARES.matcher(zh);
        if (lote.matches()) {
            return Optional.of(lote.group(1) + "-" + lote.group(2) + ", " + lote.group(3) + " " + p[3]);
        }
        return Optional.empty();
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
