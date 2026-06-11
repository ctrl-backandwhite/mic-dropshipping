package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderItemView;
import com.nexaplatform.dropshipping.api.dto.PartnerDtos.OrderView;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PartnerOrderItemDtoOut;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper translating the internal {@code OrderView}/{@code OrderItemView}
 * records (produced by {@code OrderService}) into the transport
 * {@link PartnerOrderDtoOut}. Every field is mapped explicitly. Replaces the
 * controller returning the legacy {@code OrderView} record directly.
 */
@Mapper(componentModel = "spring")
public interface PartnerOrderDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "orderNumber", source = "orderNumber")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "subtotal", source = "subtotal")
    @Mapping(target = "shipping", source = "shipping")
    @Mapping(target = "tax", source = "tax")
    @Mapping(target = "total", source = "total")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "placedAt", source = "placedAt")
    @Mapping(target = "shippedAt", source = "shippedAt")
    @Mapping(target = "items", source = "items")
    PartnerOrderDtoOut toDtoOut(OrderView view);

    List<PartnerOrderDtoOut> toDtoOutList(List<OrderView> views);

    @Mapping(target = "productId", source = "productId")
    @Mapping(target = "variantId", source = "variantId")
    @Mapping(target = "quantity", source = "quantity")
    @Mapping(target = "unitPrice", source = "unitPrice")
    @Mapping(target = "lineTotal", source = "lineTotal")
    PartnerOrderItemDtoOut toItemDtoOut(OrderItemView view);
}
