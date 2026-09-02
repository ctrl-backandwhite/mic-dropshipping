package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.PriceTierView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantOptionView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantValueView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.EuComplianceService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.locale.LocaleHolder;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProductMapper {

    private final SupplierMapper supplierMapper;
    private final PricingService pricingService;
    private final CurrencyRateService currencyRateService;
    private final MarginService marginService;
    private final EuComplianceService euComplianceService;

    public ProductSummaryView toSummary(ProductEntity p, String language) {
        if (p == null)
            return null;
        String title = pickTitle(p, language);
        String image = p.getImages().stream().findFirst().map(this::pickImageUrl).orElse(null);
        PricedAmount priced = pricingService.priceFor(p);
        Integer availableUnits = null;
        try {
            availableUnits = p.getVariants() == null
                    ? null
                    : p.getVariants().stream().filter(v -> v != null && v.isActive()).mapToInt(v -> v.getStock()).sum();
        } catch (Exception ignored) {
            /* lazy init fuera de tx → fallback a null */ }

        // Coste del proveedor SOLO para ADMIN, igual que en toDetail. `basePrice` NO es un precio de venta:
        // es el importe que se paga al proveedor en CNY, la base sobre la que PricingService aplica el
        // margen. Publicarlo junto al precio final permite a cualquiera calcular la ganancia exacta por
        // producto — y el listado lo devolvía a todo el mundo, incluida la API de partners, mientras la
        // ficha sí lo filtraba desde el principio. El escaparate ya pinta `displayFormatted`, que es la
        // única cifra que le corresponde ver.
        boolean admin = SecurityUtils.isAdmin();
        return new ProductSummaryView(p.getId(), p.getSlug(), title, image,
                admin ? p.getBasePrice() : null, admin ? p.getCurrency() : null,
                p.getRating(), p.getMonthlySales(), p.getTrendScore(),
                // `retailUsd` también SOLO para admin, por el mismo motivo y con la misma incoherencia que
                // `basePrice`: la ficha ya lo ocultaba a quien no es admin (línea ~84) y el listado lo
                // publicaba a todo el mundo. Es el precio canónico en USD antes de convertir; al cliente le
                // corresponde `displayFormatted`, en su divisa.
                p.getStatus() != null ? p.getStatus().name() : null, admin ? priced.retailUsd() : null,
                priced.displayAmount(),
                priced.displayCurrency(), priced.displaySymbol(), priced.displayFormatted(), p.getInventoryCount(),
                availableUnits, Boolean.TRUE.equals(p.getVerified()),
                // La rebaja viaja YA resuelta desde el motor de precios: el escaparate solo la pinta.
                priced.originalFormatted(), priced.discountPercent(), priced.promotionName());
    }

    public ProductDetailView toDetail(ProductEntity p, String language, List<ProductPriceTierEntity> tiers) {
        ProductTranslationEntity tr = resolveTranslation(p.getTranslations(), language);
        PricedAmount priced = pricingService.priceFor(p);
        // Coste y margen/ganancia SOLO para ADMIN. OPERATOR (soporte) y USER ven el precio de venta
        // (displayAmount/displayFormatted) pero NO el coste (costUsd), el retail USD ni el % de margen.
        boolean admin = SecurityUtils.isAdmin();
        BigDecimal costUsd = admin ? priced.costUsd() : null;
        BigDecimal retailUsd = admin ? priced.retailUsd() : null;
        BigDecimal appliedMarginPercent = admin ? priced.appliedMarginPercent() : null;
        // Coste del proveedor en la FICHA, con el mismo criterio que ya se aplicaba aquí a costUsd/retailUsd
        // y que se aplicó al listado (toSummary). `basePrice` es lo que se paga al proveedor en CNY y
        // `currency` la etiqueta que lo delata: publicados junto al precio de venta, una sola división deja
        // a la vista la ganancia exacta de cada producto. Cerrar el listado y dejar la ficha abierta no
        // tapaba nada, porque a la ficha se llega con un enlace directo. El escaparate no los necesita —solo
        // pinta `displayFormatted`, que ya viene compuesto y formateado—, mientras que el editor del admin sí
        // tarifica con ellos, así que siguen viajando para ADMIN.
        BigDecimal basePrice = admin ? p.getBasePrice() : null;
        String currency = admin ? p.getCurrency() : null;
        // Desglose base/IVA/envío/recargo: SOLO admin (el usuario final ve únicamente el total = displayFormatted).
        String baseFormatted = admin ? priced.baseFormatted() : null;
        String ivaFormatted = admin ? priced.ivaFormatted() : null;
        String shippingFormatted = admin ? priced.shippingFormatted() : null;
        // Recargo fijo por producto (30-ago-2026): el valor crudo en CNY (lo que edita el admin) y el
        // formateado. SOLO admin; el cliente solo ve displayFormatted (que ya lo incluye en el total).
        BigDecimal surchargeCny = admin ? p.getSurchargeCny() : null;
        // Las bolsas de subvención son SOLO admin: el cliente ve su efecto en el desglose del checkout,
        // nunca el importe que se les ha asignado.
        BigDecimal shippingUserCny = admin ? p.getShippingUserCny() : null;
        BigDecimal dutyUserCny = admin ? p.getDutyUserCny() : null;
        String surchargeFormatted = admin ? priced.surchargeFormatted() : null;
        String shippingUserFormatted = admin ? priced.shippingUserFormatted() : null;
        String dutyUserFormatted = admin ? priced.dutyUserFormatted() : null;
        return new ProductDetailView(p.getId(), p.getSlug(), p.getSource(), p.getExternalId(),
                p.getSupplier() != null ? supplierMapper.toView(p.getSupplier()) : null,
                p.getCategory() != null ? p.getCategory().getId() : null, tr != null ? tr.getTitle() : p.getTitleZh(),
                tr != null ? tr.getShortDescription() : p.getShortDescriptionZh(),
                tr != null ? tr.getDescription() : p.getDescriptionZh(), p.getTitleZh(), p.getShortDescriptionZh(),
                p.getDescriptionZh(), p.getBrand(), p.getMoq(), basePrice, currency, p.getRating(),
                p.getReviewCount(), p.getMonthlySales(), p.getRepurchaseRate(), p.getTrendScore(),
                p.getStatus() != null ? p.getStatus().name() : null, p.getSourceUrl(), p.getIngestedAt(),
                p.getLastSyncedAt(), p.getImages().stream().map(this::toImageView).toList(),
                p.getVariantOptions().stream().map(o -> toOptionView(o, language)).toList(),
                p.getVariants().stream().map(v -> toVariantView(p, v, language)).toList(),
                tiers == null ? Collections.emptyList() : tiers.stream().map(this::toPriceTierView).toList(),
                costUsd, retailUsd, priced.displayAmount(), priced.displayCurrency(),
                priced.displaySymbol(), priced.displayFormatted(), appliedMarginPercent,
                baseFormatted, ivaFormatted, shippingFormatted, surchargeCny, surchargeFormatted,
                shippingUserCny, dutyUserCny, shippingUserFormatted, dutyUserFormatted,
                tr != null ? tr.getMetaTitle() : null, tr != null ? tr.getMetaDescription() : null,
                Boolean.TRUE.equals(p.getVerified()),
                videoUrlOf(p), Boolean.TRUE.equals(p.getHasVideo()),
                priced.originalFormatted(), priced.discountPercent(), priced.promotionName(),
                // Cumplimiento del Reglamento (UE) 2023/988. Va en TODAS las fichas, también las del admin:
                // el art. 19 obliga a mostrarlo en la oferta, y el panel necesita el mismo bloque para saber
                // qué le falta a cada referencia.
                euComplianceService.forProduct(p.getCategory() != null ? p.getCategory().getId() : null,
                        p.getManufacturerName(), p.getManufacturerAddress(), p.getManufacturerEmail(),
                        language),
                // El arancel adicional no se resuelve aquí: depende del CARRITO de quien mira, no del
                // producto, y este mapeo va cacheado. Lo decora el controlador con la ficha ya construida,
                // y con él el indicador de quién paga el derecho, que además depende del país.
                null, null, null, false);
    }

    public ProductImageView toImageView(ProductImageEntity img) {
        return new ProductImageView(img.getId(), img.getPosition(), img.getRole(), img.getSourceUrl(), img.getCdnUrl());
    }

    public VariantView toVariantView(ProductEntity product, ProductVariantEntity v, String language) {
        // Display variant price converted via PricingService too
        PricedAmount priced = pricingService.priceFor(product, v);
        // Peso por variante: usa el del paquete (bruto) si existe, si no el neto. Dimensiones tal cual (mm).
        Integer weight = (v.getPackageWeightGrams() != null && v.getPackageWeightGrams() > 0)
                ? v.getPackageWeightGrams() : v.getWeightGrams();
        return new VariantView(v.getId(), v.getSku(), v.getTitle(), priced.displayAmount(), // shown in user currency
                priced.displayFormatted(), v.getStock(), pickVariantImage(v),
                translateVariantOptions(v.getOptions(), product, language), v.isActive(),
                weight, v.getLengthMm(), v.getWidthMm(), v.getHeightMm(),
                priced.originalFormatted(), priced.discountPercent());
    }

    /**
     * Dirección del vídeo que se le da al navegador: la NUESTRA si ya está espejado, la del proveedor si
     * todavía no.
     *
     * <p>Es la misma preferencia que las imágenes hacen con {@code cdnUrl} sobre {@code sourceUrl}. Mientras
     * el espejado no ha terminado se sigue sirviendo la del origen —vale más un vídeo de Alibaba que
     * ninguno—, y en cuanto termina la ficha deja de salir de nuestro dominio sin que haya que tocar nada.
     */
    static String videoUrlOf(ProductEntity p) {
        String propia = p.getVideoCdnUrl();
        return propia != null && !propia.isBlank() ? propia : p.getVideoUrl();
    }

    /** Back-compat overload (without product); used by ProductMapperTest. */
    public VariantView toVariantView(ProductVariantEntity v) {
        Integer weight = (v.getPackageWeightGrams() != null && v.getPackageWeightGrams() > 0)
                ? v.getPackageWeightGrams() : v.getWeightGrams();
        return new VariantView(v.getId(), v.getSku(), v.getTitle(), v.getPrice(), null, v.getStock(),
                pickVariantImage(v), v.getOptions(), v.isActive(),
                weight, v.getLengthMm(), v.getWidthMm(), v.getHeightMm());
    }

    /** Back-compat: opción sin idioma (no resuelve traducción) — usado por tests/llamadas heredadas. */
    public VariantOptionView toOptionView(VariantOptionEntity o) {
        return toOptionView(o, null);
    }

    public VariantOptionView toOptionView(VariantOptionEntity o, String language) {
        List<VariantValueView> vals = o.getValues().stream().map(v -> toValueView(v, language)).toList();
        return new VariantOptionView(o.getId(), o.getNameZh(), o.getName(), o.getPosition(), vals);
    }

    public VariantValueView toValueView(VariantValueEntity v, String language) {
        // Traducciones por idioma + override neutral (value). valueLocalized = traducción del idioma
        // pedido, si no el override neutral; el frontend cae a translateVariantCN(valueZh) si ambos faltan.
        Map<String, String> tr = new LinkedHashMap<>();
        for (VariantValueTranslationEntity t : v.getTranslations()) {
            if (t.getLanguage() != null && t.getValue() != null) {
                tr.put(t.getLanguage().toLowerCase(), t.getValue());
            }
        }
        String localized = language != null ? tr.get(language.toLowerCase()) : null;
        if (localized == null) {
            localized = v.getValue();
        }
        return new VariantValueView(v.getId(), v.getValueZh(), v.getValue(), localized, pickValueImage(v),
                v.getImageSourceUrl(), v.getPosition(), tr);
    }

    /**
     * Traduce el mapa {@code options} de UNA variante (SKU) al idioma pedido — el mismo dato que
     * {@code toOptionView}/{@code toValueView} ya traducen para el selector visual, pero que hasta ahora
     * viajaba crudo en chino para cada variante del carrito. Las claves (nombre del eje, p.ej. "Color")
     * NO se tocan: el pipeline de carga ya las traduce antes de guardarlas. Solo el VALOR ("黑色") se
     * resuelve contra {@code variant_value_translation}, emparejando por el chino igual que hace
     * {@code BulkProductFields.applyVariantValueTranslations} al importar.
     *
     * <p>Si el valor no tiene traducción para el idioma pedido —o no aparece entre los ejes del
     * producto—, se conserva el valor crudo tal cual llegó: nunca se deja el campo vacío.
     */
    private Map<String, String> translateVariantOptions(Map<String, String> rawOptions, ProductEntity product,
            String language) {
        if (rawOptions == null || rawOptions.isEmpty()) {
            return rawOptions;
        }
        Map<String, String> localizedByChineseValue = valueTranslationIndex(product, language);
        Map<String, String> translated = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : rawOptions.entrySet()) {
            String localized = localizedByChineseValue.get(e.getValue());
            translated.put(e.getKey(), localized != null && !localized.isBlank() ? localized : e.getValue());
        }
        return translated;
    }

    /** Índice valor-en-chino → valor localizado, aplanando TODOS los ejes del producto. */
    private Map<String, String> valueTranslationIndex(ProductEntity product, String language) {
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
                    index.put(vv.getValueZh(), resolveLocalizedValue(vv, language));
                }
            }
        }
        return index;
    }

    /** Idioma pedido → override neutral (value). Mismo criterio que {@link #toValueView}. */
    private String resolveLocalizedValue(VariantValueEntity v, String language) {
        if (language != null && v.getTranslations() != null) {
            for (VariantValueTranslationEntity t : v.getTranslations()) {
                if (language.equalsIgnoreCase(t.getLanguage()) && t.getValue() != null) {
                    return t.getValue();
                }
            }
        }
        return v.getValue();
    }

    public PriceTierView toPriceTierView(ProductPriceTierEntity t) {
        // El tramo se guarda en la moneda del proveedor (CNY) como COSTE, y se tarifica por la MISMA vía
        // que el precio de la ficha, el de la variante y el del pedido.
        //
        // Antes tenía su propia cuenta —coste → USD → margen— y se quedaba ahí: le faltaban el IVA y el
        // envío, que sí lleva el precio que se cobra. La ficha anunciaba «2+ → 1,99 $» y al pagar salían
        // 3,57 $ la unidad, un 79% más de lo prometido en la tabla de cantidades.
        PricedAmount priced = pricingService.priceForSupplierAmount(t.getProduct(), null, t.getUnitPrice());
        BigDecimal displayAmount = priced.displayAmount();
        String displayCode = priced.displayCurrency() != null ? priced.displayCurrency()
                : pricingService.displayCurrencyCode();
        return new PriceTierView(t.getMinQty(), t.getMaxQty(), displayAmount, displayCode,
                priced.displayFormatted() != null ? priced.displayFormatted()
                        : currencyRateService.formatDisplay(displayAmount, displayCode));
    }

    /* ------------------ helpers ------------------ */

    private String pickTitle(ProductEntity p, String language) {
        String lang = resolveLanguage(language);
        // Try requested lang → en → zh (titleZh)
        return findTranslation(p.getTranslations(), lang).map(ProductTranslationEntity::getTitle)
                .filter(s -> s != null && !s.isBlank()).or(() -> findTranslation(p.getTranslations(), "en")
                        .map(ProductTranslationEntity::getTitle).filter(s -> s != null && !s.isBlank()))
                .orElse(p.getTitleZh());
    }

    private String resolveLanguage(String requested) {
        if (requested != null && !requested.isBlank())
            return requested.toLowerCase();
        return LocaleHolder.get();
    }

    private Optional<ProductTranslationEntity> findTranslation(List<ProductTranslationEntity> ts, String lang) {
        if (ts == null || lang == null)
            return Optional.empty();
        return ts.stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage())).findFirst();
    }

    /**
     * Traducción efectiva para mostrar: idioma pedido → inglés → primera disponible. Nunca cae al
     * chino canónico salvo que no exista ninguna traducción (entonces devuelve null y el caller usa zh).
     * Así un idioma sin traducción propia (fr/de/…) ve inglés, no el título original en chino.
     */
    private ProductTranslationEntity resolveTranslation(List<ProductTranslationEntity> ts, String lang) {
        if (ts == null || ts.isEmpty()) {
            return null;
        }
        return findTranslation(ts, lang)
                .or(() -> findTranslation(ts, "en"))
                .orElseGet(() -> ts.stream().filter(t -> t.getTitle() != null && !t.getTitle().isBlank())
                        .findFirst().orElse(null));
    }

    private String pickImageUrl(ProductImageEntity img) {
        return img.getCdnUrl() != null && !img.getCdnUrl().isBlank() ? img.getCdnUrl() : img.getSourceUrl();
    }

    private String pickVariantImage(ProductVariantEntity v) {
        if (v.getImageCdnUrl() != null && !v.getImageCdnUrl().isBlank())
            return v.getImageCdnUrl();
        return v.getImageSourceUrl();
    }

    private String pickValueImage(VariantValueEntity v) {
        if (v.getImageCdnUrl() != null && !v.getImageCdnUrl().isBlank())
            return v.getImageCdnUrl();
        return v.getImageSourceUrl();
    }
}
