package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAttr;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkAxis;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkReview;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkSpec;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTier;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkTranslation;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn.BulkVariant;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantOptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.VariantValueTranslationEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Inverse of the bulk import: maps a persisted {@link ProductEntity} (with its sub-entities) back to a
 * {@link BulkProductDtoIn}, so the exported JSON has the exact same shape used to create products and can
 * be re-imported (round-trip). Must be invoked inside a read-only transaction — it walks lazy collections.
 */
@Component
public class ProductBulkExportMapper {

    public BulkProductDtoIn toBulk(ProductEntity p, List<ProductAttributeEntity> attributes,
            List<ProductSpecificationEntity> specifications, List<ProductPriceTierEntity> tiers,
            List<ProductReviewEntity> reviews) {
        BulkProductDtoIn d = new BulkProductDtoIn();

        if (p.getCategory() != null) {
            d.setCategorySlug(p.getCategory().getSlug());
            d.setCategory1688Id(p.getCategory().getExternalId());
        }
        if (p.getSupplier() != null) {
            d.setSupplierExternalId(p.getSupplier().getExternalId());
            d.setSupplierName(p.getSupplier().getName());
        }
        d.setManufacturer(p.getBrand());
        d.setExternalId(p.getExternalId());
        d.setStatus(p.getStatus() != null ? p.getStatus().name() : null);

        // Canonical fixed-language fields (the unlimited `translations` map below carries every language).
        d.setTitleZh(p.getTitleZh());
        d.setDescriptionZh(p.getDescriptionZh());
        Map<String, ProductTranslationEntity> byLang = new LinkedHashMap<>();
        for (ProductTranslationEntity t : safe(p.getTranslations())) {
            if (t.getLanguage() != null) {
                byLang.put(t.getLanguage().toLowerCase(), t);
            }
        }
        d.setTitleEs(title(byLang, "es"));
        d.setTitleEn(title(byLang, "en"));
        d.setTitlePt(title(byLang, "pt"));
        d.setDescriptionEs(desc(byLang, "es"));
        d.setDescriptionEn(desc(byLang, "en"));
        d.setDescriptionPt(desc(byLang, "pt"));

        d.setPrice(p.getBasePrice());
        d.setMoq(p.getMoq());
        d.setMonthlySales(p.getMonthlySales());
        d.setRating(p.getRating());

        // imageUrls del export: se prefiere la URL de origen, pero si falta (p.ej. imagen añadida solo
        // con cdn_url) se cae a la cdn_url espejada. Así reexportar→reimportar conserva las imágenes y
        // el producto no se rechaza por "sin imágenes" en el round-trip.
        d.setImageUrls(safe(p.getImages()).stream()
                .sorted(Comparator.comparingInt(ProductImageEntity::getPosition))
                .map(img -> img.getSourceUrl() != null && !img.getSourceUrl().isBlank()
                        ? img.getSourceUrl() : img.getCdnUrl())
                .filter(java.util.Objects::nonNull).toList());

        // Logistics / customs (direct columns).
        d.setWeightGrams(p.getWeightGrams());
        d.setPackageWeightGrams(p.getPackageWeightGrams());
        d.setLengthMm(p.getLengthMm());
        d.setWidthMm(p.getWidthMm());
        d.setHeightMm(p.getHeightMm());
        d.setCountryOfOrigin(p.getCountryOfOrigin());
        d.setHsCode(p.getHsCode());
        d.setCertifications(p.getCertifications());
        d.setShipFrom(p.getShipFrom());
        d.setLeadTimeDays(p.getLeadTimeDays());
        d.setVideoUrl(p.getVideoUrl());
        d.setVideoUrls(p.getVideoUrls());
        d.setSalesRegions(p.getSalesRegions());
        d.setRatingBreakdown(p.getRatingBreakdown());
        d.setCrossBorderSupport(p.getCrossBorderSupport());
        d.setDropshipShipped30d(p.getDropshipShipped30d());
        d.setDropshipPickupRate48h(p.getDropshipPickupRate48h());

        d.setTieredPricing(safe(tiers).stream()
                .map(t -> new BulkTier(t.getMinQty(), t.getMaxQty(), t.getUnitPrice(), t.getCurrency())).toList());

        d.setVariantAxes(safe(p.getVariantOptions()).stream()
                .sorted(Comparator.comparingInt(VariantOptionEntity::getPosition)).map(this::axis).toList());
        d.setVariants(safe(p.getVariants()).stream().map(this::variant).toList());

        d.setAttributes(safe(attributes).stream()
                .map(a -> new BulkAttr(a.getAttrKey(), a.getAttrValue(), a.getLocale())).toList());
        d.setSpecifications(safe(specifications).stream()
                .map(s -> new BulkSpec(s.getLocale(), s.getSpecKey(), s.getSpecValue(), s.getPosition())).toList());

        // Unlimited translations map (every stored language, including es/en/pt/zh).
        Map<String, BulkTranslation> translations = new LinkedHashMap<>();
        for (ProductTranslationEntity t : safe(p.getTranslations())) {
            if (t.getLanguage() != null) {
                translations.put(t.getLanguage(),
                        new BulkTranslation(t.getTitle(), t.getShortDescription(), t.getDescription()));
            }
        }
        d.setTranslations(translations.isEmpty() ? null : translations);

        d.setReviews(safe(reviews).stream().map(this::review).toList());
        return d;
    }

    private BulkAxis axis(VariantOptionEntity o) {
        List<String> values = new ArrayList<>();
        Map<String, String> valueImages = new LinkedHashMap<>();
        Map<String, Map<String, String>> valueTranslations = new LinkedHashMap<>();
        for (VariantValueEntity v : safe(o.getValues()).stream()
                .sorted(Comparator.comparingInt(VariantValueEntity::getPosition)).toList()) {
            String value = v.getValue() != null ? v.getValue() : v.getValueZh();
            values.add(value);
            if (v.getImageSourceUrl() != null && value != null) {
                valueImages.put(value, v.getImageSourceUrl());
            }
            String key = v.getValueZh() != null ? v.getValueZh() : value;
            Map<String, String> tr = new LinkedHashMap<>();
            for (VariantValueTranslationEntity t : safe(v.getTranslations())) {
                if (t.getLanguage() != null) {
                    tr.put(t.getLanguage(), t.getValue());
                }
            }
            if (!tr.isEmpty() && key != null) {
                valueTranslations.put(key, tr);
            }
        }
        return new BulkAxis(o.getName() != null ? o.getName() : o.getNameZh(), values,
                valueImages.isEmpty() ? null : valueImages, valueTranslations.isEmpty() ? null : valueTranslations);
    }

    private BulkVariant variant(ProductVariantEntity v) {
        BulkVariant b = new BulkVariant();
        b.setSku(v.getSku());
        b.setOptionValues(v.getOptions());
        b.setPrice(v.getPrice());
        b.setStock(v.getStock());
        b.setImageUrl(v.getImageSourceUrl());
        b.setSupplierSkuId(v.getSupplierSkuId());
        b.setWeightGrams(v.getWeightGrams());
        b.setPackageWeightGrams(v.getPackageWeightGrams());
        b.setLengthMm(v.getLengthMm());
        b.setWidthMm(v.getWidthMm());
        b.setHeightMm(v.getHeightMm());
        return b;
    }

    private BulkReview review(ProductReviewEntity r) {
        List<String> tags = (r.getTags() == null || r.getTags().isBlank())
                ? null
                : java.util.Arrays.stream(r.getTags().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        return new BulkReview(r.getAuthorName(), r.getAuthorCountry(), (int) r.getRating(), r.getTitle(), r.getBody(),
                r.getLanguage(), r.isVerifiedPurchase(), tags);
    }

    private String title(Map<String, ProductTranslationEntity> byLang, String lang) {
        ProductTranslationEntity t = byLang.get(lang);
        return t != null ? t.getTitle() : null;
    }

    private String desc(Map<String, ProductTranslationEntity> byLang, String lang) {
        ProductTranslationEntity t = byLang.get(lang);
        return t != null ? t.getDescription() : null;
    }

    private <T> List<T> safe(List<T> list) {
        return list != null ? list : List.of();
    }
}
