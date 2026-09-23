package com.nexaplatform.dropshipping.domain.enums;

import java.util.Locale;
import java.util.Map;

/**
 * Nombre de un eje de variación —«Color», «Talla»— en los ocho idiomas.
 *
 * <p>El eje se guarda con el nombre en español en {@code variant_option}, porque así llega de la
 * carga, y se servía tal cual: la ficha en inglés enseñaba «Color / Talla» y el comprador leía media
 * frase en un idioma que no había elegido. Los VALORES ya se traducían —tienen su tabla—; el nombre
 * del eje no tenía dónde.
 *
 * <p>Solo se traduce lo que es un eje CONOCIDO. La cola de nombres libres —«Talla de calcetín
 * infantil», «Medidas (largo x ancho en cm)»— se queda como está: inventarle una traducción sería
 * peor que dejarla, y son menos del 3 % de los ejes del catálogo.
 */
public enum VariantAxisLabel {

    COLOR("Color", new Translations("Color", "Color", "Cor", "颜色", "Couleur", "Farbe", "Colore", "Kleur")), TALLA(
            "Talla",
            new Translations("Talla", "Size", "Tamanho", "尺码", "Taille", "Größe", "Taglia", "Maat")), TAMANO("Tamaño",
                    new Translations("Tamaño", "Size", "Tamanho", "尺寸", "Taille", "Größe", "Dimensione",
                            "Formaat")), MEDIDAS(
                                    "Medidas",
                                    new Translations("Medidas", "Measurements", "Medidas", "尺寸规格", "Dimensions", "Maße",
                                            "Misure", "Afmetingen")), ESTAMPADO(
                                                    "Estampado",
                                                    new Translations("Estampado", "Pattern", "Estampado", "图案", "Motif",
                                                            "Muster", "Fantasia", "Print")), MODELO(
                                                                    "Modelo",
                                                                    new Translations("Modelo", "Model", "Modelo", "型号",
                                                                            "Modèle", "Modell", "Modello",
                                                                            "Model")), CAPACIDAD(
                                                                                    "Capacidad",
                                                                                    new Translations("Capacidad",
                                                                                            "Capacity", "Capacidade",
                                                                                            "容量", "Capacité",
                                                                                            "Kapazität", "Capacità",
                                                                                            "Capaciteit")), MATERIAL(
                                                                                                    "Material",
                                                                                                    new Translations(
                                                                                                            "Material",
                                                                                                            "Material",
                                                                                                            "Material",
                                                                                                            "材质",
                                                                                                            "Matière",
                                                                                                            "Material",
                                                                                                            "Materiale",
                                                                                                            "Materiaal")), ESTILO(
                                                                                                                    "Estilo",
                                                                                                                    new Translations(
                                                                                                                            "Estilo",
                                                                                                                            "Style",
                                                                                                                            "Estilo",
                                                                                                                            "款式",
                                                                                                                            "Style",
                                                                                                                            "Stil",
                                                                                                                            "Stile",
                                                                                                                            "Stijl")), ALTURA_RECOMENDADA(
                                                                                                                                    "Altura recomendada",
                                                                                                                                    new Translations(
                                                                                                                                            "Altura recomendada",
                                                                                                                                            "Recommended height",
                                                                                                                                            "Altura recomendada",
                                                                                                                                            "建议身高",
                                                                                                                                            "Taille recommandée",
                                                                                                                                            "Empfohlene Körpergröße",
                                                                                                                                            "Altezza consigliata",
                                                                                                                                            "Aanbevolen lengte"));

    private static final Map<String, VariantAxisLabel> POR_NOMBRE = Map.ofEntries(Map.entry("color", COLOR),
            Map.entry("talla", TALLA), Map.entry("tamaño", TAMANO), Map.entry("tamano", TAMANO),
            Map.entry("medidas", MEDIDAS), Map.entry("estampado", ESTAMPADO), Map.entry("modelo", MODELO),
            Map.entry("capacidad", CAPACIDAD), Map.entry("material", MATERIAL), Map.entry("estilo", ESTILO),
            Map.entry("altura recomendada", ALTURA_RECOMENDADA));

    private final String spanish;
    private final Translations translations;

    VariantAxisLabel(String spanish, Translations translations) {
        this.spanish = spanish;
        this.translations = translations;
    }

    public String spanish() {
        return spanish;
    }

    /**
     * El nombre del eje en ese idioma, o el que venga si no es un eje conocido.
     *
     * @param stored   nombre guardado, en español
     * @param language idioma pedido; {@code null} o desconocido devuelve lo guardado
     */
    public static String localize(String stored, String language) {
        if (stored == null || stored.isBlank() || language == null || language.isBlank()) {
            return stored;
        }
        VariantAxisLabel axis = POR_NOMBRE.get(stored.trim().toLowerCase(Locale.ROOT));
        return axis == null ? stored : axis.translations.of(language);
    }

    /** Las 8 traducciones de un eje, agrupadas para no arrastrar 8 parámetros sueltos (java:S107). */
    private record Translations(String es, String en, String pt, String zh, String fr, String de, String it,
            String nl) {

        String of(String lang) {
            return switch (lang == null ? "" : lang.trim().toLowerCase(Locale.ROOT)) {
                case "en" -> en;
                case "pt" -> pt;
                case "zh" -> zh;
                case "fr" -> fr;
                case "de" -> de;
                case "it" -> it;
                case "nl" -> nl;
                default -> es;
            };
        }
    }
}
