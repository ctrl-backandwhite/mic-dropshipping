package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.MeOrderAddressDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderItemDetailDtoOut;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * API-layer mapper projecting the {@link Order} domain model into the authenticated
 * user's order detail DtoOut. Injected in the controller. Money is converted from
 * integer cents to BigDecimal (4 dp); the per-line display title is already resolved
 * by the use case into {@code titleSnapshot} (request-language fallback chain). The
 * address blocks are built from the order's flat snapshot fields.
 */
@Mapper(componentModel = "spring")
public interface MeOrderDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "externalOrderId", source = "externalOrderId")
    @Mapping(target = "status", expression = "java(model.getStatus() != null ? model.getStatus().name() : null)")
    @Mapping(target = "subtotal", source = "subtotalCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "shipping", source = "shippingCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "tax", source = "taxCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "total", source = "totalCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "shippingAddress", expression = "java(shippingAddress(model))")
    @Mapping(target = "billingAddress", expression = "java(billingAddress(model))")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "trackingCarrier", ignore = true)
    @Mapping(target = "trackingNumber", ignore = true)
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "deliveredAt", source = "deliveredAt")
    @Mapping(target = "cancelledAt", source = "cancelledAt")
    @Mapping(target = "items", source = "items")
    MeOrderDetailDtoOut toDetailDtoOut(Order model);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "variantId", source = "variantId")
    @Mapping(target = "productTitle", source = "titleSnapshot")
    @Mapping(target = "variantName", source = "variantName")
    @Mapping(target = "imageUrl", expression = "java(image(item))")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPriceCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "lineTotal", source = "lineTotalCents", qualifiedByName = "centsToDecimal")
    MeOrderItemDetailDtoOut toItemDetail(OrderItem item);

    /** Prefers a live catalog image over the snapshot (often a placeholder or empty). */
    default String image(OrderItem item) {
        String image = item.getImageUrlSnapshot();
        if ((image == null || image.isBlank()) && item.getProductImageUrl() != null) {
            image = item.getProductImageUrl();
        }
        return image;
    }

    /** Builds the shipping address block from the order's flat snapshot fields. */
    default MeOrderAddressDtoOut shippingAddress(Order model) {
        if (model.getShippingFullName() == null && model.getShippingLine1() == null) {
            return null;
        }
        return MeOrderAddressDtoOut.builder()
                .fullName(model.getShippingFullName())
                .phone(model.getShippingPhone())
                .email(model.getShippingEmail())
                .line1(model.getShippingLine1())
                .line2(model.getShippingLine2())
                .city(model.getShippingCity())
                .state(model.getShippingState())
                .postalCode(model.getShippingPostalCode())
                .country(model.getShippingCountry())
                .build();
    }

    /** Builds the billing address block from the order's flat snapshot fields (nullable). */
    default MeOrderAddressDtoOut billingAddress(Order model) {
        if (model.getBillingFullName() == null && model.getBillingLine1() == null) {
            return null;
        }
        return MeOrderAddressDtoOut.builder()
                .fullName(model.getBillingFullName())
                .phone(model.getBillingPhone())
                .email(model.getBillingEmail())
                .line1(model.getBillingLine1())
                .line2(model.getBillingLine2())
                .city(model.getBillingCity())
                .state(model.getBillingState())
                .postalCode(model.getBillingPostalCode())
                .country(model.getBillingCountry())
                .build();
    }

    /** Converts integer cents to a 4-dp BigDecimal amount. */
    @Named("centsToDecimal")
    default BigDecimal centsToDecimal(int cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }

    List<MeOrderItemDetailDtoOut> toItemDetails(List<OrderItem> items);
}
