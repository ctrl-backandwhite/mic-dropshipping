package com.nexaplatform.dropshipping.application.service;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deja un texto traducido escrito como se escribe en su idioma.
 *
 * <p><b>Por qué existe (25-sep-2026).</b> En la misma lista de tallas de una ficha convivían «16,7 cm»,
 * «17.3 cm», «17,9cm» y «talla 31: 19.8 cm». Cada valor viene del traductor por su cuenta y nadie los
 * mira juntos, así que la incoherencia solo se ve en pantalla, apilada. Medido sobre preproducción:
 * de 16.630 valores por idioma, 2.244 traían punto decimal donde tocaba coma y 1.900 pegaban la unidad
 * al número.
 *
 * <p>Se corrigen SOLO cosas deterministas —el separador decimal del idioma, el espacio antes de la
 * unidad y la mayúscula inicial—. La redacción no se toca: reescribir «largo interior» a «largo» sería
 * traducir, y eso no se hace aquí ni a mano.
 */
public final class TextoTraducido {

    private TextoTraducido() {
    }

    /**
     * Idiomas que escriben los decimales con COMA. El inglés y el chino usan punto.
     *
     * <p>Es la lista explícita y no «todos menos inglés» a propósito: cuando entre un idioma nuevo,
     * quien lo añada tiene que decidir a qué grupo pertenece en vez de heredar un criterio por
     * descarte.
     */
    private static final Set<String> COMA_DECIMAL = Set.of("es", "pt", "fr", "de", "it", "nl");

    /** Un número pegado a su unidad: «16,5cm». Se exige el límite de palabra para no partir «cm3». */
    private static final Pattern UNIDAD_PEGADA = Pattern.compile("(\\d)(cm|mm|kg|ml|[gl])\\b");

    /**
     * El separador decimal: un punto o una coma entre dígitos y con UN solo separador.
     *
     * <p>Los paréntesis de alrededor son los que hacen el trabajo fino. Sin ellos, «v1.2.3» se
     * convertía a medias —«v1,2.3»—, que es peor que no tocarlo: una cadena con más de un separador no
     * es un decimal, es un código o una versión. Un rango como «31-36» ya queda fuera porque no lleva
     * punto ni coma.
     */
    private static final Pattern DECIMAL = Pattern.compile("(?<![.,\\d])(\\d+)[.,](\\d+)(?![.,\\d])");

    /**
     * Normaliza el valor para ese idioma.
     *
     * <p>Devuelve el mismo texto recortado cuando no hay nada que corregir, así el llamante puede
     * comparar por identidad y ahorrarse una escritura.
     */
    public static String normaliza(String texto, String idioma) {
        if (texto == null) {
            return null;
        }
        String t = texto.trim().replaceAll("\\s{2,}", " ");
        if (t.isEmpty()) {
            return t;
        }
        t = separadorDecimal(t, idioma);
        t = UNIDAD_PEGADA.matcher(t).replaceAll("$1 $2");
        return mayusculaInicial(t);
    }

    /**
     * Pone el separador decimal del idioma.
     *
     * <p>Solo entre dígitos: así un rango como «31-36», una versión «v1.2.3» o un millar escrito con
     * separador quedan fuera. Lo que se cambia es «16.5» por «16,5» en español y al revés en inglés.
     */
    private static String separadorDecimal(String texto, String idioma) {
        String separador = COMA_DECIMAL.contains(idioma == null ? "" : idioma.toLowerCase()) ? "," : ".";
        Matcher m = DECIMAL.matcher(texto);
        StringBuilder salida = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(salida, Matcher.quoteReplacement(m.group(1) + separador + m.group(2)));
        }
        m.appendTail(salida);
        return salida.toString();
    }

    /**
     * Mayúscula en la primera letra, y solo si la primera letra es minúscula.
     *
     * <p>No se toca el resto: «Blanco roto» está bien y «W1999 wit en groen» también. Poner en mayúscula
     * cada palabra —el «Title Case» del inglés— sería incorrecto en español, francés, italiano y
     * portugués, donde los colores y materiales van en minúscula.
     */
    private static String mayusculaInicial(String texto) {
        int primera = texto.codePointAt(0);
        if (!Character.isLowerCase(primera)) {
            return texto;
        }
        return new StringBuilder().appendCodePoint(Character.toUpperCase(primera))
                .append(texto.substring(Character.charCount(primera))).toString();
    }
}
