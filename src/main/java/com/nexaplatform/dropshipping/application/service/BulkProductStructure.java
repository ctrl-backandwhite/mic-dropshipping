package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantValue;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAxis;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTier;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkVariant;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Estructura de venta que se deriva de una fila de carga: ejes de variación, variantes y tramos de precio.
 *
 * <p>Nada se inventa. Si el proveedor no declara stock, la variante nace a cero en vez de con un número
 * cómodo; si no declara tramos, la ficha muestra sólo el precio unitario. Un catálogo de dropshipping con
 * datos inventados vende lo que no puede servir.
 */
public final class BulkProductStructure {

    private BulkProductStructure() {
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * Ejes de variación (Color, Talla...) con sus valores y, si el proveedor la trae, la foto de cada
     * valor.
     *
     * <p>Cuando la fila no declara ejes pero las variantes traen {@code optionValues}, los ejes se DERIVAN
     * de ahí: cada clave es un eje y sus valores distintos son los valores, conservando el orden de
     * aparición. Sin esto, un alta que sólo trae variantes se quedaba sin selector en la ficha y el
     * comprador no podía elegir color ni talla.
     */
    public static List<IngestVariantOption> variantOptionsOf(BulkProductDtoIn r) {
        List<IngestVariantOption> options = new ArrayList<>();
        if (r.getVariantAxes() != null) {
            for (BulkAxis ax : r.getVariantAxes()) {
                if (!has(ax.getName())) {
                    continue;
                }
                options.add(new IngestVariantOption(ax.getName(), options.size(), valuesOf(ax)));
            }
        }
        if (options.isEmpty()) {
            options.addAll(deriveFromVariants(r));
        }
        return options;
    }

    private static List<IngestVariantValue> valuesOf(BulkAxis ax) {
        List<IngestVariantValue> values = new ArrayList<>();
        if (ax.getValues() == null) {
            return values;
        }
        int position = 0;
        for (String val : ax.getValues()) {
            // DROP-674: imagen real por valor (p.ej. la foto del color), si el proveedor la trae.
            String img = ax.getValueImages() != null ? ax.getValueImages().get(val) : null;
            values.add(new IngestVariantValue(val, position++, has(img) ? img : null));
        }
        return values;
    }

    private static List<IngestVariantOption> deriveFromVariants(BulkProductDtoIn r) {
        List<IngestVariantOption> options = new ArrayList<>();
        if (r.getVariants() == null) {
            return options;
        }
        LinkedHashMap<String, LinkedHashSet<String>> derived = new LinkedHashMap<>();
        for (BulkVariant v : r.getVariants()) {
            if (v.getOptionValues() == null) {
                continue;
            }
            for (Map.Entry<String, String> e : v.getOptionValues().entrySet()) {
                if (has(e.getKey()) && has(e.getValue())) {
                    derived.computeIfAbsent(e.getKey().trim(), k -> new LinkedHashSet<>()).add(e.getValue().trim());
                }
            }
        }
        for (Map.Entry<String, LinkedHashSet<String>> en : derived.entrySet()) {
            List<IngestVariantValue> values = new ArrayList<>();
            int position = 0;
            for (String val : en.getValue()) {
                values.add(new IngestVariantValue(val, position++, null));
            }
            options.add(new IngestVariantOption(en.getKey(), options.size(), values));
        }
        return options;
    }

    /**
     * Variantes comprables. Cada una lleva SU precio real: un producto de 1688 con varios colores no
     * cuesta lo mismo en todos, y cobrar un precio único haría perder dinero en los caros.
     *
     * <p>Sin variantes declaradas se crea una por defecto para que el producto se pueda comprar, con
     * stock 0 porque el stock real es desconocido y no se inventa (DROP-680).
     */
    public static List<IngestVariant> variantsOf(BulkProductDtoIn r, String externalId, String title,
            BigDecimal fallbackPrice) {
        List<IngestVariant> variants = new ArrayList<>();
        if (r.getVariants() == null || r.getVariants().isEmpty()) {
            variants.add(new IngestVariant(externalId + "-DEF", externalId + "-DEF", title, fallbackPrice, 0, null,
                    Map.of()));
            return variants;
        }
        int index = 0;
        for (BulkVariant v : r.getVariants()) {
            // Sin SKU del proveedor se genera uno estable a partir del identificador externo: hace falta
            // para poder emparejar la variante en una reimportación posterior.
            String sku = has(v.getSku()) ? v.getSku() : externalId + "-" + (index + 1);
            variants.add(new IngestVariant(sku, sku, title,
                    v.getPrice() != null ? v.getPrice() : fallbackPrice,
                    v.getStock() != null ? v.getStock() : 0,
                    v.getImageUrl(), v.getOptionValues() != null ? v.getOptionValues() : Map.of()));
            index++;
        }
        return variants;
    }

    /**
     * Tramos de precio por cantidad (DROP-669). Se persisten SÓLO los que declara el proveedor: si no hay,
     * la ficha muestra únicamente el precio unitario en vez de un descuento por volumen inventado.
     */
    public static List<IngestPriceTier> priceTiersOf(BulkProductDtoIn r, BigDecimal fallbackPrice) {
        List<IngestPriceTier> tiers = new ArrayList<>();
        if (r.getTieredPricing() == null || r.getTieredPricing().isEmpty()) {
            return tiers;
        }
        for (BulkTier t : r.getTieredPricing()) {
            tiers.add(new IngestPriceTier(t.getMinQty() != null ? t.getMinQty() : 1, t.getMaxQty(),
                    t.getUnitPrice() != null ? t.getUnitPrice() : fallbackPrice,
                    t.getCurrency() != null ? t.getCurrency() : "CNY"));
        }
        return tiers;
    }
}
