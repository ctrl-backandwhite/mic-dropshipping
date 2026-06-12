package com.nexaplatform.dropshipping.api.dto.out;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderItemEntity;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * A line item inside the authenticated user's order detail. Field names mirror
 * the legacy {@code OrderItemDetailView} record the controller exposed.
 */
@Value
@Builder
public class MeOrderItemDetailDtoOut {

    UUID id;
    UUID productId;
    UUID variantId;
    String productTitle;
    String variantName;
    String imageUrl;
    int quantity;
    BigDecimal unitPrice;
    BigDecimal lineTotal;

    public static MeOrderItemDetailDtoOut from(OrderItemEntity i) {
        return from(i, "es");
    }

    /**
     * DROP-537: prefer the user-language translation over the Chinese snapshot
     * stored in {@code order_item.title_snapshot}. Fallback chain:
     * product_translation[lang] → product_translation['en'] → snapshot → titleZh.
     */
    public static MeOrderItemDetailDtoOut from(OrderItemEntity i, String lang) {
        BigDecimal unit = BigDecimal.valueOf(i.getUnitPriceCents()).divide(BigDecimal.valueOf(100), 4,
                RoundingMode.HALF_UP);
        BigDecimal total = BigDecimal.valueOf(i.getLineTotalCents()).divide(BigDecimal.valueOf(100), 4,
                RoundingMode.HALF_UP);
        String title = null;
        if (i.getProduct() != null) {
            var trs = i.getProduct().getTranslations();
            if (trs != null) {
                title = trs.stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null)
                        .map(t -> t.getTitle()).findFirst().orElse(null);
                if (title == null) {
                    title = trs.stream().filter(t -> "en".equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null)
                            .map(t -> t.getTitle()).findFirst().orElse(null);
                }
            }
        }
        if (title == null) {
            title = i.getTitleSnapshot() != null
                    ? i.getTitleSnapshot()
                    : (i.getProduct() != null ? i.getProduct().getTitleZh() : null);
        }
        String variantName = (i.getVariant() != null) ? i.getVariant().getTitle() : null;
        // Prefer a live catalog image over the snapshot (often placeholder or empty).
        String image = i.getImageUrlSnapshot();
        if ((image == null || image.isBlank()) && i.getProduct() != null && i.getProduct().getImages() != null
                && !i.getProduct().getImages().isEmpty()) {
            var img = i.getProduct().getImages().get(0);
            image = img.getCdnUrl() != null && !img.getCdnUrl().isBlank() ? img.getCdnUrl() : img.getSourceUrl();
        }
        return MeOrderItemDetailDtoOut.builder().id(i.getId())
                .productId(i.getProduct() != null ? i.getProduct().getId() : null)
                .variantId(i.getVariant() != null ? i.getVariant().getId() : null).productTitle(title)
                .variantName(variantName).imageUrl(image).quantity(i.getQuantity()).unitPrice(unit).lineTotal(total)
                .build();
    }
}
