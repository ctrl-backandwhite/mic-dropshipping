package com.nexaplatform.dropshipping.api.dto.out;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
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
    // DROP-637: precios de línea ya formateados por el backend en la moneda mostrada.
    String unitPriceFormatted;
    String lineTotalFormatted;

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
        String variantName = (i.getVariant() != null) ? i.getVariant().getTitle() : null;
        return MeOrderItemDetailDtoOut.builder().id(i.getId())
                .productId(i.getProduct() != null ? i.getProduct().getId() : null)
                .variantId(i.getVariant() != null ? i.getVariant().getId() : null).productTitle(resolveTitle(i, lang))
                .variantName(variantName).imageUrl(resolveImage(i)).quantity(i.getQuantity()).unitPrice(unit)
                .lineTotal(total).build();
    }

    /**
     * Título de la línea siguiendo la cadena de respaldo de DROP-537: traducción al idioma pedido,
     * traducción al inglés, instantánea guardada en el pedido y, como último recurso, el título chino.
     * La instantánea va por delante del chino porque refleja lo que el cliente vio al comprar.
     */
    private static String resolveTitle(OrderItemEntity i, String lang) {
        String fromTranslations = translatedTitle(i, lang);
        if (fromTranslations != null) {
            return fromTranslations;
        }
        // La foto que guardó el pedido manda sobre el producto vivo: lo vendido no cambia porque
        // alguien reedite la ficha después.
        if (i.getTitleSnapshot() != null) {
            return i.getTitleSnapshot();
        }
        return i.getProduct() != null ? i.getProduct().getTitleZh() : null;
    }

    /** Traducción del título en el idioma pedido; si no existe, la inglesa; si tampoco, {@code null}. */
    private static String translatedTitle(OrderItemEntity i, String lang) {
        if (i.getProduct() == null || i.getProduct().getTranslations() == null) {
            return null;
        }
        List<ProductTranslationEntity> trs = i.getProduct().getTranslations();
        String exact = firstTitleIn(trs, lang);
        return exact != null ? exact : firstTitleIn(trs, "en");
    }

    private static String firstTitleIn(List<ProductTranslationEntity> trs, String lang) {
        return trs.stream().filter(t -> lang.equalsIgnoreCase(t.getLanguage()) && t.getTitle() != null)
                .map(ProductTranslationEntity::getTitle).findFirst().orElse(null);
    }

    /**
     * Imagen de la línea: la instantánea del pedido y, si vino vacía (caso habitual en pedidos
     * antiguos o con marcador de posición), la primera del catálogo vivo, con el CDN por delante
     * del origen para no servir la URL de 1688.
     */
    private static String resolveImage(OrderItemEntity i) {
        String image = i.getImageUrlSnapshot();
        if (image != null && !image.isBlank()) {
            return image;
        }
        if (i.getProduct() == null || i.getProduct().getImages() == null || i.getProduct().getImages().isEmpty()) {
            return image;
        }
        ProductImageEntity img = i.getProduct().getImages().get(0);
        return img.getCdnUrl() != null && !img.getCdnUrl().isBlank() ? img.getCdnUrl() : img.getSourceUrl();
    }
}
