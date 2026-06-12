package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemView;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderView;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderItemDtoOut;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * API-layer mapper translating the {@link Order} domain model into the transport
 * {@link PartnerOrderDtoOut} (and the legacy {@code OrderView} record still consumed
 * by the inbound shop webhook). Injected in the controller. Money is converted from
 * integer cents to BigDecimal (4 dp), preserving the legacy contract.
 */
@Mapper(componentModel = "spring")
public interface PartnerOrderDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", expression = "java(model.getStatus() != null ? model.getStatus().name() : null)")
    @Mapping(target = "subtotal", source = "subtotalCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "shipping", source = "shippingCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "tax", source = "taxCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "total", source = "totalCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "items", source = "items")
    PartnerOrderDtoOut toDtoOut(Order model);

    List<PartnerOrderDtoOut> toDtoOutList(List<Order> models);

    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "variantId", source = "variantId")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPriceCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "lineTotal", source = "lineTotalCents", qualifiedByName = "centsToDecimal")
    PartnerOrderItemDtoOut toItemDtoOut(OrderItem model);

    /* ============ Legacy OrderView (inbound shop webhook) ============ */

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", expression = "java(model.getStatus() != null ? model.getStatus().name() : null)")
    @Mapping(target = "subtotal", source = "subtotalCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "shipping", source = "shippingCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "tax", source = "taxCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "total", source = "totalCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "items", source = "items")
    OrderView toOrderView(Order model);

    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "variantId", source = "variantId")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPriceCents", qualifiedByName = "centsToDecimal")
    @Mapping(target = "lineTotal", source = "lineTotalCents", qualifiedByName = "centsToDecimal")
    OrderItemView toItemView(OrderItem model);

    /** Converts integer cents to a 4-dp BigDecimal amount. */
    @Named("centsToDecimal")
    default BigDecimal centsToDecimal(int cents) {
        return BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
    }
}
