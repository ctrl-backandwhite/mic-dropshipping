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

    /** Para pasar un importe a porcentaje. */
    private static final BigDecimal CIEN = new BigDecimal("100");

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
        // Nulo es «no lo toques», no «desmárcalo»: una importación por JSON que no traiga el campo no
        // puede descertificar un producto que ya estaba verificado en el destino. Por el bus llega
        // siempre con valor, que es lo que hace que producción muestre lo mismo que preproducción.
        if (r.getVerified() != null) {
            p.setVerified(r.getVerified());
        }
        if (has(r.getVideoUrl())) {
            // cambiarVideoUrl y no setVideoUrl: si la dirección es otra, deja el vídeo en cola para
            // espejarlo y descarta lo que hubiera espejado del anterior.
            p.cambiarVideoUrl(r.getVideoUrl());
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
        if (r.getRepurchaseRate() != null) {
            p.setRepurchaseRate(r.getRepurchaseRate());
        }
        if (r.getReviewsSummary() != null && !r.getReviewsSummary().isBlank()) {
            p.setReviewsSummary(r.getReviewsSummary());
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
        Map<String, BulkProductDtoIn.BulkVariant> bySku = variantsBySku(r);
        if (bySku.isEmpty()) {
            return;
        }
        for (ProductVariantEntity pv : p.getVariants()) {
            BulkProductDtoIn.BulkVariant v = bySku.get(pv.getSku());
            if (v != null) {
                copyVariantLogistics(pv, v, r.getSupplierShipping());
            }
        }
    }

    /**
     * El recargo del producto, que SOBREVIVE a una reimportación.
     *
     * <p>Antes era {@code r.getSurchargeCny() != null ? ... : ZERO}, y eso devolvía el recargo a
     * cero en cada pasada. El catálogo se reimporta constantemente —al escribir esto había 10.157
     * productos en cola para recargarse— así que el trabajo del panel duraba hasta la siguiente
     * extracción, sin ningún error: sólo un precio que vuelve a ser el de antes.
     *
     * <p>Es la misma regla que el cambio del 23-sep-2026 declara para los tramos: <b>nulo NO es
     * cero</b>. Un bulk que no habla del recargo no está pidiendo que se borre; uno que manda un
     * cero explícito sí, porque el cero es un valor que alguien ha decidido escribir.
     *
     * <p>Desde el 25-sep-2026 lo que se guarda es un PORCENTAJE sobre el coste. La columna nueva es
     * nulable, así que ya no hace falta poner cero al producto nuevo para esquivar el 23502 de la
     * vieja: nulo aporta cero en el cálculo y significa «nadie ha fijado recargo aquí».
     */
    public static void applySurcharge(ProductEntity p, BulkProductDtoIn r) {
        BigDecimal pct = surchargePctDe(r);
        if (pct != null) {
            p.setSurchargePct(pct);
        }
    }

    /**
     * El recargo de la fila en porcentaje, convirtiendo el importe viejo si es lo único que trae.
     *
     * <p>Los JSON de carga anteriores al 25-sep-2026 llevan {@code surchargeCny}, un importe absoluto en
     * yuanes. Se convierte contra el precio del proveedor de esa misma fila, que es la base sobre la que
     * ahora se aplica el porcentaje, para que el producto salga al mismo precio que salía.
     *
     * <p>Devuelve nulo cuando la fila no habla del recargo: entonces no se toca lo que hubiera. Ausente
     * NO es cero — poner cero borraría en silencio el recargo que alguien fijó a mano en el panel.
     */
    private static BigDecimal surchargePctDe(BulkProductDtoIn r) {
        if (r.getSurchargePct() != null) {
            return r.getSurchargePct();
        }
        BigDecimal importeViejo = r.getSurchargeCny();
        if (importeViejo == null) {
            return null;
        }
        // Un cero explícito NO necesita la base para convertirse: cero por ciento de cualquier coste es
        // cero. Sin este atajo, un JSON viejo que borra el recargo mandando 0 y sin precio no lo borraba.
        if (importeViejo.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal base = r.getPrice();
        if (base == null || base.signum() <= 0) {
            return null;
        }
        return importeViejo.multiply(CIEN).divide(base, 3, RoundingMode.HALF_UP);
    }

    /**
     * El recargo que le toca a un tramo al recrearlo en una reimportación.
     *
     * <p>{@code replacePriceTiers} borra y recrea los tramos en cada pasada. El scraper no manda
     * recargo de tramo —y hace bien, lo fija el panel— así que el valor llegaba nulo y el tramo
     * renacía sin él. Es peor que el del producto, porque nadie revisa los tramos uno a uno.
     *
     * <p>Aquí nulo SÍ es el valor correcto para lo que no existía, al revés que en el producto: la
     * columna del tramo es NULLABLE a propósito y nulo significa «usa el del producto». Ponerlo a
     * cero le daría a cada tramo nuevo un recargo de cero que nadie decidió.
     *
     * @param anteriores recargos que tenían los tramos de este producto, por {@code minQty}
     */
    public static BigDecimal tierSurcharge(Map<Integer, BigDecimal> anteriores, int minQty, BigDecimal delBulk) {
        if (delBulk != null) {
            return delBulk;
        }
        return anteriores != null ? anteriores.get(minQty) : null;
    }

    /** Índice SKU → variante de la fila. Una variante sin SKU no se puede emparejar, así que se descarta. */
    private static Map<String, BulkProductDtoIn.BulkVariant> variantsBySku(BulkProductDtoIn r) {
        if (r.getVariants() == null) {
            return Map.of();
        }
        Map<String, BulkProductDtoIn.BulkVariant> bySku = new HashMap<>();
        for (BulkProductDtoIn.BulkVariant v : r.getVariants()) {
            if (has(v.getSku())) {
                bySku.put(v.getSku(), v);
            }
        }
        return bySku;
    }

    /** Copia campo a campo; cada uno sólo si la fila lo trae, porque un null es "no hablo de esto". */
    private static void copyVariantLogistics(ProductVariantEntity pv, BulkProductDtoIn.BulkVariant v,
            BulkProductDtoIn.BulkSupplierShipping delProducto) {
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
        // El cero SÍ se guarda: un proveedor con envío gratis declara 0, y tratarlo como ausencia
        // dejaría puesto el importe anterior y cobraría un flete que nadie paga.
        if (v.getShippingCny() != null) {
            pv.setShippingCny(v.getShippingCny());
        }
        // La del producto PRIMERO y la de la variante encima: la tarifa es del proveedor, así que
        // la variante que no declara la suya hereda la de su producto, y la que la declara manda.
        copySupplierRate(pv, delProducto);
        copySupplierRate(pv, v.getSupplierShipping());
    }

    /**
     * La tarifa del proveedor, si el bulk habla de ella.
     *
     * <p>Misma regla que el recargo y que los tramos: <b>nulo NO es cero</b>. Un crawler que no
     * mande la tarifa no está pidiendo que se borre, y sólo el 67% de las fichas se puede sondar
     * —los 7.649 productos cargados antes de que la sonda existiera no la traen—. Borrarla en cada
     * pasada dejaría la tarifa medida viviendo hasta la siguiente reimportación.
     *
     * <p>El incremento CERO sí se guarda: hay proveedores con porte plano, y tratarlo como
     * ausencia dejaría puesto el incremento anterior y cobraría de más en cada unidad extra.
     */
    private static void copySupplierRate(ProductVariantEntity pv, BulkProductDtoIn.BulkSupplierShipping t) {
        if (t == null) {
            return;
        }
        if (t.getFirstUnitCny() != null) {
            pv.setSupplierShipFirstCny(t.getFirstUnitCny());
        }
        if (t.getExtraUnitCny() != null) {
            pv.setSupplierShipExtraCny(t.getExtraUnitCny());
        }
    }

    /**
     * Traducciones de los valores de variación (Color, Talla) por idioma. Se emparejan por el valor en
     * chino, que es la clave estable que viene del proveedor, y REEMPLAZAN las del valor: reimportar es la
     * forma de corregir una traducción mala, así que acumularlas dejaría la vieja conviviendo con la nueva.
     */
    public static void applyVariantValueTranslations(ProductEntity p, BulkProductDtoIn r) {
        Map<String, Map<String, String>> byValue = translationsByChineseValue(r);
        if (byValue.isEmpty()) {
            return;
        }
        for (VariantOptionEntity opt : p.getVariantOptions()) {
            for (VariantValueEntity vv : opt.getValues()) {
                replaceValueTranslations(vv, byValue.get(vv.getValueZh()));
            }
        }
    }

    /** Traducciones de TODOS los ejes de la fila en un solo índice valor-chino → (idioma → texto). */
    private static Map<String, Map<String, String>> translationsByChineseValue(BulkProductDtoIn r) {
        if (r.getVariantAxes() == null) {
            return Map.of();
        }
        Map<String, Map<String, String>> byValue = new HashMap<>();
        for (BulkProductDtoIn.BulkAxis ax : r.getVariantAxes()) {
            if (ax.getValueTranslations() != null) {
                byValue.putAll(ax.getValueTranslations());
            }
        }
        return byValue;
    }

    /** Reemplaza las traducciones del valor. Si la fila no habla de él, se deja intacto (no se vacía). */
    private static void replaceValueTranslations(VariantValueEntity vv, Map<String, String> trMap) {
        if (trMap == null || trMap.isEmpty()) {
            return;
        }
        vv.getTranslations().clear();
        for (Map.Entry<String, String> e : trMap.entrySet()) {
            if (has(e.getKey()) && has(e.getValue())) {
                String idioma = e.getKey().trim().toLowerCase();
                // Si el ORIGINAL chino es un patrón conocido —una talla con su largo interior, un lote
                // de pares—, manda la forma canónica y no lo que devolvió el traductor. El chino es
                // uniforme y el traductor no: el mismo «26码内长16.7» llegaba de treinta formas por
                // idioma, y algunas mal («plantilla», «entrepierna», que no es lo que dice 内长).
                //
                // Lo que no encaja en un patrón se queda como vino, solo con la ortografía corregida:
                // ahí el traductor es la única fuente y reescribirlo sería inventar.
                //
                // Y se hace al ENTRAR, no al pintar: en la vista dejaría al buscador, al export y al
                // bus viendo el texto viejo.
                String texto = TallaCanonica.para(vv.getValueZh(), idioma)
                        .orElseGet(() -> TextoTraducido.normaliza(e.getValue(), idioma));
                vv.getTranslations().add(
                        VariantValueTranslationEntity.builder().variantValue(vv).language(idioma).value(texto).build());
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
            String shortDesc = Texts.firstNonBlankOr(tr.getTitle(), tr.getShortDescription(), tr.getDescription())
                    .trim();
            existing.setShortDescription(shortDesc.length() > MAX_SHORT_DESCRIPTION
                    ? shortDesc.substring(0, MAX_SHORT_DESCRIPTION)
                    : shortDesc);
            existing.setDescription(tr.getDescription() != null ? tr.getDescription().trim() : shortDesc);
        }
    }
}
