package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.WarehouseUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.WarehouseStockDtoOut;
import com.nexaplatform.dropshipping.domain.model.Warehouse;
import com.nexaplatform.dropshipping.domain.model.WarehouseStock;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the warehouse aggregate: translates the {@link Warehouse}
 * / {@link WarehouseStock} domain models into the transport DTOs. Injected in the
 * controller. DtoOut field names preserve the exact JSON keys the frontend
 * already consumes.
 */
@Mapper(componentModel = "spring")
public interface WarehouseDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "active", source = "active")
    WarehouseDtoOut toDtoOut(Warehouse model);

    List<WarehouseDtoOut> toDtoOutList(List<Warehouse> models);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "city", source = "city")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    Warehouse toDomain(WarehouseUpsertDtoIn req);

    @Mapping(target = "warehouseId", source = "warehouseId")
    @Mapping(target = "warehouseCode", source = "warehouseCode")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "stock", source = "stock")
    WarehouseStockDtoOut toStockDtoOut(WarehouseStock model);

    List<WarehouseStockDtoOut> toStockDtoOutList(List<WarehouseStock> models);
}
