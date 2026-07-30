package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;

import java.util.regex.Pattern;

/**
 * Título y descripción para buscadores, por idioma.
 *
 * <p>Se generan a partir de las traducciones reales del producto, y sólo si faltan o están contaminadas:
 * un metadato escrito a mano por el operador no se pisa.
 *
 * <p>«Contaminado» tiene un significado concreto aquí (DROP-686): los volcados de 1688 cuelan ideogramas
 * en textos que ya están traducidos —una ficha en español que acaba con 露趾—, y eso llega tal cual al
 * resultado de Google. Por eso, para un idioma que no es el chino, un metadato con ideogramas se
 * considera inservible y se regenera limpiándolos.
 */
public final class ProductSeoMetadata {

    /** Longitud a partir de la cual Google recorta el título en el resultado de búsqueda. */
    public static final int TITLE_SOFT_LIMIT = 65;

    /** Lo que admite la columna del título. */
    public static final int TITLE_MAX = 200;

    /** Longitud a partir de la cual Google recorta la descripción. */
    public static final int DESCRIPTION_MAX = 155;

    /** Ideogramas CJK. Precompilado: el patrón equivalente con {@code matches()} retrocede sobre todo el texto. */
    private static final Pattern CJK = Pattern.compile("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]");

    private ProductSeoMetadata() {
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    /** ¿El texto lleva ideogramas chinos, japoneses o coreanos? */
    public static boolean hasCjk(String text) {
        return text != null && CJK.matcher(text).find();
    }

    /** Genera lo que falte en cada traducción del producto. */
    public static void generate(ProductEntity p) {
        for (ProductTranslationEntity tr : p.getTranslations()) {
            boolean zh = "zh".equalsIgnoreCase(tr.getLanguage());
            String title = has(tr.getTitle()) ? sanitize(tr.getTitle(), zh) : null;
            if (!has(title)) {
                continue;   // sin título traducido no hay nada de lo que derivar el SEO
            }
            if (needsRegenerating(tr.getMetaTitle(), zh)) {
                tr.setMetaTitle(metaTitleOf(title, p.getBrand()));
            }
            if (needsRegenerating(tr.getMetaDescription(), zh)) {
                tr.setMetaDescription(metaDescriptionOf(tr, title, zh));
            }
        }
    }

    /** Falta, o lleva ideogramas en un idioma que no es el chino (texto colado del origen). */
    private static boolean needsRegenerating(String current, boolean zh) {
        return !has(current) || (!zh && hasCjk(current));
    }

    /**
     * Título con la marca al final, si cabe. Añadirla ayuda al posicionamiento, pero pasarse del límite
     * hace que Google recorte justo por ahí y el resultado se lea peor que sin marca.
     */
    private static String metaTitleOf(String title, String brand) {
        String metaTitle = title;
        if (has(brand) && !title.toLowerCase().contains(brand.toLowerCase())
                && (metaTitle.length() + brand.length() + 3) <= TITLE_SOFT_LIMIT) {
            metaTitle = metaTitle + " | " + brand.trim();
        }
        return metaTitle.length() > TITLE_MAX ? metaTitle.substring(0, TITLE_MAX) : metaTitle;
    }

    /** Descripción corta, o la larga, o el propio título; recortada donde Google recorta. */
    private static String metaDescriptionOf(ProductTranslationEntity tr, String title, boolean zh) {
        String base = has(tr.getShortDescription()) ? tr.getShortDescription()
                : (tr.getDescription() != null ? tr.getDescription() : title);
        String metaDescription = sanitize(base, zh);
        if (metaDescription.length() > DESCRIPTION_MAX) {
            metaDescription = metaDescription.substring(0, DESCRIPTION_MAX - 3).trim() + "…";
        }
        return metaDescription;
    }

    /**
     * DROP-686: en idiomas que no son el chino, quita los ideogramas que se cuelan del origen y limpia lo
     * que queda detrás —separadores duplicados o colgando— para que el texto no acabe en " | " suelto.
     */
    public static String sanitize(String text, boolean zh) {
        if (text == null) {
            return "";
        }
        String t = text;
        if (!zh) {
            t = t.replaceAll("[\\u3000-\\u303F\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF\\uFF00-\\uFFEF]", " ");
            t = t.replaceAll("\\s*([|·,;])\\s*([|·,;])", " $1 "); // separadores duplicados
            t = t.replaceAll("\\s*([|·])\\s*$", "");             // separador colgante final
            t = t.replaceAll("^\\s*([|·,;])\\s*", "");           // separador colgante inicial
        }
        return t.replaceAll("\\s+", " ").trim();
    }
}
