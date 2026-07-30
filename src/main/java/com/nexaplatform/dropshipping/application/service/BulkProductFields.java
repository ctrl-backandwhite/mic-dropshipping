package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Volcado de los campos de una fila de carga sobre el producto ya persistido.
 *
 * <p>Todos siguen la misma regla: <b>lo que la fila no trae, no se toca</b>. Un {@code null} significa
 * "este import no habla de ese campo", no "bórralo". Importa porque el importador hace UPSERT: una
 * reimportación parcial —por ejemplo la que sólo rellena pesos— no puede llevarse por delante la partida
 * arancelaria ni el vídeo que ya estaban puestos.
 *
 * <p>Estaban dentro de las 213 líneas de {@code applyLogistics}, mezclados unos con otros. Separados por
 * qué describen (medidas, aduana, ficha comercial, reseñas) se ve de un vistazo qué actualiza cada
 * import, y el desglose de estrellas —el único bloque con cálculo de verdad— queda a la vista.
 */
public final class BulkProductFields {

    /** Lo que admite la columna de descripción corta. */
    public static final int MAX_SHORT_DESCRIPTION = 2000;

    private BulkProductFields() {
    }

    private static boolean has(String s) {
        return s != null && !s.isBlank();
    }

    /** Peso y medidas del paquete: lo que el transportista necesita para cotizar. */
    public static void applyPackageDimensions(ProductEntity p, BulkProductDtoIn r) {
        if (r.getPackageWeightGrams() != null) {
            p.setPackageWeightGrams(r.getPackageWeightGrams());
        }
        if (r.getLengthMm() != null) {
            p.setLengthMm(r.getLengthMm());
        }
        if (r.getWidthMm() != null) {
            p.setWidthMm(r.getWidthMm());
        }
        if (r.getHeightMm() != null) {
            p.setHeightMm(r.getHeightMm());
        }
    }

    /**
     * Datos de aduana. El tipo de batería se normaliza a mayúsculas porque se compara con constantes al
     * decidir si el bulto viaja por un canal restringido.
     */
    public static void applyCustomsFields(ProductEntity p, BulkProductDtoIn r) {
        if (has(r.getCountryOfOrigin())) {
            p.setCountryOfOrigin(r.getCountryOfOrigin());
        }
        if (has(r.getHsCode())) {
            p.setHsCode(r.getHsCode());
        }
        if (has(r.getCustomsMaterial())) {
            p.setCustomsMaterial(r.getCustomsMaterial());
        }
        if (has(r.getCustomsUsage())) {
            p.setCustomsUsage(r.getCustomsUsage());
        }
        if (has(r.getBatteryType())) {
            p.setBatteryType(r.getBatteryType().trim().toUpperCase());
        }
    }

    /** Ficha comercial: certificaciones, origen del envío, plazo, vídeos y datos de dropshipping. */
    public static void applyCommercialFields(ProductEntity p, BulkProductDtoIn r) {
        if (r.getCertifications() != null && !r.getCertifications().isEmpty()) {
            p.setCertifications(r.getCertifications());
        }
        if (has(r.getShipFrom())) {
            p.setShipFrom(r.getShipFrom());
        }
        if (r.getLeadTimeDays() != null) {
            p.setLeadTimeDays(r.getLeadTimeDays());
        }
        if (has(r.getVideoUrl())) {
            p.setVideoUrl(r.getVideoUrl());
            p.setHasVideo(true);
        }
        if (r.getVideoUrls() != null && !r.getVideoUrls().isEmpty()) {
            p.setVideoUrls(r.getVideoUrls());
            p.setHasVideo(true);
        }
        if (r.getSalesRegions() != null && !r.getSalesRegions().isEmpty()) {
            p.setSalesRegions(r.getSalesRegions());
        }
        if (r.getCrossBorderSupport() != null && !r.getCrossBorderSupport().isEmpty()) {
            p.setCrossBorderSupport(r.getCrossBorderSupport());
        }
        if (r.getDropshipShipped30d() != null) {
            p.setDropshipShipped30d(r.getDropshipShipped30d());
        }
        if (r.getDropshipPickupRate48h() != null) {
            p.setDropshipPickupRate48h(r.getDropshipPickupRate48h());
        }
    }

    /**
     * Desglose de reseñas por estrellas (DROP-676/680). De él se derivan el número de reseñas —la suma— y,
     * SÓLO si el proveedor no declaró una media explícita, la media ponderada. Nada se inventa: si el
     * desglose no viene, el producto se queda con lo que ya tuviera.
     *
     * <p>Las claves llegan como texto desde JSON y a veces traen basura; una clave que no sea un número
     * se ignora en lugar de tumbar la importación de la fila entera.
     */
    public static void applyRatingBreakdown(ProductEntity p, BulkProductDtoIn r) {
        Map<String, Integer> breakdown = r.getRatingBreakdown();
        if (breakdown == null || breakdown.isEmpty()) {
            return;
        }
        p.setRatingBreakdown(breakdown);
        int total = 0;
        long weighted = 0;
        for (Map.Entry<String, Integer> e : breakdown.entrySet()) {
            int stars;
            try {
                stars = Integer.parseInt(e.getKey().trim());
            } catch (NumberFormatException ignored) {
                continue;
            }
            int count = e.getValue() != null ? e.getValue() : 0;
            total += count;
            weighted += (long) stars * count;
        }
        p.setReviewCount(total);
        if (r.getRating() == null && total > 0) {
            p.setRating(BigDecimal.valueOf((double) weighted / total).setScale(2, RoundingMode.HALF_UP));
        }
    }

    /**
     * Peso, medidas e identificador de proveedor POR VARIANTE, emparejados por SKU sobre las variantes que
     * el importador ya creó. Es lo que permite que una reimportación de sólo pesos —la "báscula"— actualice
     * cada talla o color sin tocar nada más; declarar el peso del producto genérico hace que el
     * transportista cotice mal y reclame la diferencia después.
     */
    public static void applyVariantLogistics(ProductEntity p, BulkProductDtoIn r) {
        if (r.getVariants() == null || r.getVariants().isEmpty()) {
            return;
        }
        Map<String, BulkProductDtoIn.BulkVariant> bySku = new HashMap<>();
        for (BulkProductDtoIn.BulkVariant v : r.getVariants()) {
            if (has(v.getSku())) {
                bySku.put(v.getSku(), v);
            }
        }
        if (bySku.isEmpty()) {
            return;
        }
        for (ProductVariantEntity pv : p.getVariants()) {
            BulkProductDtoIn.BulkVariant v = bySku.get(pv.getSku());
            if (v == null) {
                continue;
            }
            if (has(v.getSupplierSkuId())) {
                pv.setSupplierSkuId(v.getSupplierSkuId());
            }
            if (v.getWeightGrams() != null) {
                pv.setWeightGrams(v.getWeightGrams());
            }
            if (v.getPackageWeightGrams() != null) {
                pv.setPackageWeightGrams(v.getPackageWeightGrams());
            }
            if (v.getLengthMm() != null) {
                pv.setLengthMm(v.getLengthMm());
            }
            if (v.getWidthMm() != null) {
                pv.setWidthMm(v.getWidthMm());
            }
            if (v.getHeightMm() != null) {
                pv.setHeightMm(v.getHeightMm());
            }
        }
    }

    /**
     * Traducciones de los valores de variación (Color, Talla) por idioma. Se emparejan por el valor en
     * chino, que es la clave estable que viene del proveedor, y REEMPLAZAN las del valor: reimportar es la
     * forma de corregir una traducción mala, así que acumularlas dejaría la vieja conviviendo con la nueva.
     */
    public static void applyVariantValueTranslations(ProductEntity p, BulkProductDtoIn r) {
        if (r.getVariantAxes() == null) {
            return;
        }
        Map<String, Map<String, String>> byValue = new HashMap<>();
        for (BulkProductDtoIn.BulkAxis ax : r.getVariantAxes()) {
            if (ax.getValueTranslations() != null) {
                byValue.putAll(ax.getValueTranslations());
            }
        }
        if (byValue.isEmpty()) {
            return;
        }
        for (VariantOptionEntity opt : p.getVariantOptions()) {
            for (VariantValueEntity vv : opt.getValues()) {
                Map<String, String> trMap = byValue.get(vv.getValueZh());
                if (trMap == null || trMap.isEmpty()) {
                    continue;
                }
                vv.getTranslations().clear();
                for (Map.Entry<String, String> e : trMap.entrySet()) {
                    if (has(e.getKey()) && has(e.getValue())) {
                        vv.getTranslations().add(VariantValueTranslationEntity.builder().variantValue(vv)
                                .language(e.getKey().trim().toLowerCase()).value(e.getValue().trim()).build());
                    }
                }
            }
        }
    }

    /**
     * Contenido por idioma SIN límite de idiomas: el escritor fija es/en/pt/zh desde los campos fijos y
     * aquí se upsertan los del mapa {@code translations} para cualquier otro (fr, de, it, nl...). Si el
     * idioma ya existe se actualiza en vez de duplicarse, porque el catálogo se reimporta a menudo.
     *
     * <p>La descripción corta se capa a 2000 caracteres: es lo que admite la columna, y una descripción
     * larga de 1688 la desbordaba y tumbaba la fila.
     */
    public static void applyExtraTranslations(ProductEntity p, BulkProductDtoIn r) {
        if (r.getTranslations() == null || r.getTranslations().isEmpty()) {
            return;
        }
        for (Map.Entry<String, BulkProductDtoIn.BulkTranslation> e : r.getTranslations().entrySet()) {
            String lang = e.getKey() != null ? e.getKey().trim().toLowerCase() : null;
            BulkProductDtoIn.BulkTranslation tr = e.getValue();
            if (lang == null || lang.isEmpty() || tr == null || !has(tr.getTitle())) {
                continue;
            }
            final String language = lang;
            ProductTranslationEntity existing = p.getTranslations().stream()
                    .filter(t -> language.equalsIgnoreCase(t.getLanguage())).findFirst().orElse(null);
            if (existing == null) {
                existing = ProductTranslationEntity.builder().product(p).language(language).provider("bulk").build();
                p.getTranslations().add(existing);
            }
            existing.setTitle(tr.getTitle().trim());
            String shortDesc = Texts
                    .firstNonBlankOr(tr.getTitle(), tr.getShortDescription(), tr.getDescription()).trim();
            existing.setShortDescription(
                    shortDesc.length() > MAX_SHORT_DESCRIPTION ? shortDesc.substring(0, MAX_SHORT_DESCRIPTION)
                            : shortDesc);
            existing.setDescription(tr.getDescription() != null ? tr.getDescription().trim() : shortDesc);
        }
    }
}
