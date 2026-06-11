package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminOrderAddressDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderLineDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminOrderRowDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeOrderRowDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AddressEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderItemEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * MapStruct mapper for the Admin Orders API boundary. Maps the JPA entity into
 * the flat DtoOut contract; cross-entity fields (customerEmail, shopName,
 * shopHandle, supplierName) are enriched by {@code OrderService} via the
 * Lombok {@code toBuilder()} after the base mapping runs.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AdminOrderMapper {

    @Mapping(target = "status", expression = "java(order.getStatus().name())")
    @Mapping(target = "itemCount", expression = "java(order.getItems().size())")
    AdminOrderRowDtoOut toRow(CustomerOrderEntity order);

    @Mapping(target = "sku", source = "skuSnapshot")
    @Mapping(target = "title", expression = "java(resolveTitle(item))")
    @Mapping(target = "qty", source = "quantity")
    AdminOrderLineDtoOut toLine(OrderItemEntity item);

    List<AdminOrderLineDtoOut> toLines(List<OrderItemEntity> items);

    @Mapping(target = "region", source = "state")
    AdminOrderAddressDtoOut toAddress(AddressEntity address);

    @Mapping(target = "status", expression = "java(order.getStatus().name())")
    @Mapping(target = "itemCount", expression = "java(order.getItems().size())")
    MeOrderRowDtoOut toMeRow(CustomerOrderEntity order);

    /** Title fallback: explicit snapshot, then live product titleZh, then sku. */
    default String resolveTitle(OrderItemEntity item) {
        if (item.getTitleSnapshot() != null) {
            return item.getTitleSnapshot();
        }
        if (item.getProduct() != null) {
            return item.getProduct().getTitleZh();
        }
        return item.getSkuSnapshot();
    }
}
