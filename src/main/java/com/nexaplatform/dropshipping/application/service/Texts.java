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
}
