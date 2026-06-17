package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.PriceTierView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantOptionView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantValueView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
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
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProductMapper {

    private final SupplierMapper supplierMapper;
    private final PricingService pricingService;
    private final CurrencyRateService currencyRateService;

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

        return new ProductSummaryView(p.getId(), p.getSlug(), title, image, p.getBasePrice(), p.getCurrency(),
                p.getRating(), p.getMonthlySales(), p.getTrendScore(),
                p.getStatus() != null ? p.getStatus().name() : null, priced.retailUsd(), priced.displayAmount(),
                priced.displayCurrency(), priced.displaySymbol(), p.getInventoryCount(), availableUnits);
    }

    public ProductDetailView toDetail(ProductEntity p, String language, List<ProductPriceTierEntity> tiers) {
        ProductTranslationEntity tr = resolveTranslation(p.getTranslations(), language);
        PricedAmount priced = pricingService.priceFor(p);
        return new ProductDetailView(p.getId(), p.getSlug(), p.getSource(), p.getExternalId(),
                p.getSupplier() != null ? supplierMapper.toView(p.getSupplier()) : null,
                p.getCategory() != null ? p.getCategory().getId() : null, tr != null ? tr.getTitle() : p.getTitleZh(),
                tr != null ? tr.getShortDescription() : p.getShortDescriptionZh(),
                tr != null ? tr.getDescription() : p.getDescriptionZh(), p.getTitleZh(), p.getShortDescriptionZh(),
                p.getDescriptionZh(), p.getBrand(), p.getMoq(), p.getBasePrice(), p.getCurrency(), p.getRating(),
                p.getReviewCount(), p.getMonthlySales(), p.getRepurchaseRate(), p.getTrendScore(),
                p.getStatus() != null ? p.getStatus().name() : null, p.getSourceUrl(), p.getIngestedAt(),
                p.getLastSyncedAt(), p.getImages().stream().map(this::toImageView).toList(),
                p.getVariantOptions().stream().map(o -> toOptionView(o, language)).toList(),
                p.getVariants().stream().map(v -> toVariantView(p, v)).toList(),
                tiers == null ? Collections.emptyList() : tiers.stream().map(this::toPriceTierView).toList(),
                priced.costUsd(), priced.retailUsd(), priced.displayAmount(), priced.displayCurrency(),
                priced.displaySymbol(), priced.appliedMarginPercent(),
                tr != null ? tr.getMetaTitle() : null, tr != null ? tr.getMetaDescription() : null);
    }

    public ProductImageView toImageView(ProductImageEntity img) {
        return new ProductImageView(img.getId(), img.getPosition(), img.getRole(), img.getSourceUrl(), img.getCdnUrl());
    }

    public VariantView toVariantView(ProductEntity product, ProductVariantEntity v) {
        // Display variant price converted via PricingService too
        PricedAmount priced = pricingService.priceFor(product, v);
        return new VariantView(v.getId(), v.getSku(), v.getTitle(), priced.displayAmount(), // shown in user currency
                v.getStock(), pickVariantImage(v), v.getOptions(), v.isActive());
    }

    /** Back-compat overload (without product); used by ProductMapperTest. */
    public VariantView toVariantView(ProductVariantEntity v) {
        return new VariantView(v.getId(), v.getSku(), v.getTitle(), v.getPrice(), v.getStock(), pickVariantImage(v),
                v.getOptions(), v.isActive());
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
        java.util.Map<String, String> tr = new java.util.LinkedHashMap<>();
        for (var t : v.getTranslations()) {
            if (t.getLanguage() != null && t.getValue() != null) {
                tr.put(t.getLanguage().toLowerCase(), t.getValue());
            }
        }
        String localized = language != null ? tr.get(language.toLowerCase()) : null;
        if (localized == null) {
            localized = v.getValue();
        }
        return new VariantValueView(v.getId(), v.getValueZh(), v.getValue(), localized, pickValueImage(v),
                v.getPosition(), tr);
    }

    public PriceTierView toPriceTierView(ProductPriceTierEntity t) {
        // Tier prices are stored in CNY (supplier currency); convert to display
        BigDecimal usd = currencyRateService.toUsd(t.getUnitPrice(), t.getCurrency() != null ? t.getCurrency() : "CNY");
        // (margin not applied to tiered B2B costs here — tiers reflect supplier ladder)
        BigDecimal displayAmount = currencyRateService.usdToDisplay(usd);
        return new PriceTierView(t.getMinQty(), t.getMaxQty(), displayAmount, pricingService.displayCurrencyCode());
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
