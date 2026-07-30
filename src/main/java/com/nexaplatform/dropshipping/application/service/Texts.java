package com.nexaplatform.dropshipping.application.service;

/**
 * Elección del primer texto utilizable de una lista de candidatos.
 *
 * <p>Es un patrón que aparece por todo el proyecto: un dato tiene varias fuentes con prioridad —lo que
 * guardó el pedido, lo que dice el producto, lo que hay por defecto— y se toma la primera que traiga
 * algo. Estaba escrito con ternarios anidados, donde el orden de prioridad —que es la parte que
 * importa— quedaba enterrado entre paréntesis.
 *
 * <p>«Utilizable» significa no nulo y no en blanco: una cadena vacía es un campo sin rellenar, no un
 * valor. Tomarla por buena deja huecos en facturas y declaraciones de aduana.
 */
public final class Texts {

    private Texts() {
    }

    /** ¿Tiene contenido? (no nulo y no sólo espacios) */
    public static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Primer candidato con contenido, en el orden dado.
     *
     * @return el primero utilizable, o {@code null} si ninguno lo es
     */
    public static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (has(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Igual que {@link #firstNonBlank}, pero garantizando respuesta: el último argumento es el valor por
     * defecto y se devuelve tal cual si ninguno de los anteriores sirve.
     */
    public static String firstNonBlankOr(String fallback, String... candidates) {
        String found = firstNonBlank(candidates);
        return found != null ? found : fallback;
    }

    /**
     * Quita las barras finales de una URL base, para poder concatenar rutas sin acabar con "//".
     *
     * <p>Se hace recorriendo hacia atrás y no con {@code replaceAll("/+$", "")}: ese patrón obliga al
     * motor de expresiones regulares a reintentar desde cada posición cuando la cadena NO termina en
     * barra, que es el caso normal. Aquí se leen sólo los caracteres finales.
     *
     * <p>Estaba repetido en doce sitios (almacenamiento, índices de búsqueda, conectores de tienda,
     * facturas, OAuth), unos con regex y otros con su propio helper privado. Una sola versión evita que
     * media docena de copias se separen con el tiempo.
     */
    public static String stripTrailingSlashes(String url) {
        if (url == null) {
            return "";
        }
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
