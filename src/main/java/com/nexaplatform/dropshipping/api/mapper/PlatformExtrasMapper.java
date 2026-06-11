package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.OdmProjectDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PlatformNotificationDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.PodDesignDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SupportTicketDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseStockDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.NotificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OdmProjectEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PodDesignEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductWarehouseStockEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupportTicketEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WarehouseEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * MapStruct mapper for the Platform extras API boundary (POD, ODM, tickets,
 * notifications, warehouses). Combined with Lombok: entity getters and DtoOut
 * builders are Lombok-generated and consumed by the MapStruct-generated impl.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface PlatformExtrasMapper {

    /* ---------- POD ---------- */

    @Mapping(source = "product.id", target = "productId")
    @Mapping(source = "product.titleZh", target = "productTitle")
    PodDesignDtoOut toPodDesignDto(PodDesignEntity entity);

    List<PodDesignDtoOut> toPodDesignDtos(List<PodDesignEntity> entities);

    /* ---------- ODM ---------- */

    OdmProjectDtoOut toOdmDto(OdmProjectEntity entity);

    List<OdmProjectDtoOut> toOdmDtos(List<OdmProjectEntity> entities);

    /* ---------- Tickets ---------- */

    @Mapping(source = "order.id", target = "orderId")
    SupportTicketDtoOut toTicketDto(SupportTicketEntity entity);

    List<SupportTicketDtoOut> toTicketDtos(List<SupportTicketEntity> entities);

    /* ---------- Notifications ---------- */

    PlatformNotificationDtoOut toNotificationDto(NotificationEntity entity);

    List<PlatformNotificationDtoOut> toNotificationDtos(List<NotificationEntity> entities);

    /* ---------- Warehouses ---------- */

    WarehouseDtoOut toWarehouseDto(WarehouseEntity entity);

    List<WarehouseDtoOut> toWarehouseDtos(List<WarehouseEntity> entities);

    @Mapping(source = "warehouse.id", target = "warehouseId")
    @Mapping(source = "warehouse.code", target = "warehouseCode")
    @Mapping(source = "warehouse.country", target = "country")
    WarehouseStockDtoOut toStockDto(ProductWarehouseStockEntity entity);

    List<WarehouseStockDtoOut> toStockDtos(List<ProductWarehouseStockEntity> entities);
}
