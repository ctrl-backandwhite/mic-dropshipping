package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduce las opciones que lleva una variante ({@code options_json}) al idioma de la petición.
 *
 * <p>Ese JSON guarda SIEMPRE el texto original del proveedor —«黑色»—, y la traducción vive aparte,
 * en los valores del eje ({@code variant_value_translation}). Quien quiera enseñar la variante tiene
 * que cruzar las dos cosas.
 *
 * <p>Está aquí, y no dentro de un mapeador, porque lo necesitan al menos dos: la ficha de catálogo y
 * el pedido. Mientras solo lo tuvo el catálogo, el pedido del cliente, el del panel y la compra al
 * proveedor enseñaban el chino crudo de un pedido ya pagado.
 */
public final class VariantOptionTranslator {

    private VariantOptionTranslator() {
    }

    /** Las mismas opciones, con cada valor traducido cuando hay traducción para él. */
    public static Map<String, String> translate(Map<String, String> rawOptions, ProductEntity product,
            String language) {
        if (rawOptions == null || rawOptions.isEmpty()) {
            return rawOptions;
        }
        Map<String, String> localizedByChineseValue = index(product, language);
        Map<String, String> translated = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : rawOptions.entrySet()) {
            String localized = localizedByChineseValue.get(e.getValue());
            translated.put(e.getKey(), localized != null && !localized.isBlank() ? localized : e.getValue());
        }
        return translated;
    }

    /** Del texto original del proveedor al texto en el idioma pedido. */
    private static Map<String, String> index(ProductEntity product, String language) {
        Map<String, String> index = new LinkedHashMap<>();
        if (product == null || product.getVariantOptions() == null) {
            return index;
        }
        for (VariantOptionEntity opt : product.getVariantOptions()) {
            if (opt.getValues() == null) {
                continue;
            }
            for (VariantValueEntity vv : opt.getValues()) {
                if (vv.getValueZh() != null) {
                    index.put(vv.getValueZh(), localized(vv, language));
                }
            }
        }
        return index;
    }

    private static String localized(VariantValueEntity v, String language) {
        if (language != null && v.getTranslations() != null) {
            for (VariantValueTranslationEntity t : v.getTranslations()) {
                if (language.equalsIgnoreCase(t.getLanguage()) && t.getValue() != null) {
                    return t.getValue();
                }
            }
        }
        return v.getValue();
    }
}
