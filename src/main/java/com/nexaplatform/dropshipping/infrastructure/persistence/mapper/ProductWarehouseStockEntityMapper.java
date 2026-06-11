package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.WarehouseStock;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductWarehouseStockEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the read-only {@link WarehouseStock} domain
 * model and the JPA entity. Builder disabled so MapStruct uses setters. The
 * warehouse identity is flattened from the {@code warehouse} relation
 * ({@code warehouseId}, {@code warehouseCode}, {@code country}); this read model
 * has no {@code toEntity} counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface ProductWarehouseStockEntityMapper {

    @Mapping(target = "warehouseId", source = "warehouse.id")
    @Mapping(target = "warehouseCode", source = "warehouse.code")
    @Mapping(target = "country", source = "warehouse.country")
    @Mapping(target = "stock", source = "stock")
    WarehouseStock toDomain(ProductWarehouseStockEntity entity);

    List<WarehouseStock> toDomainList(List<ProductWarehouseStockEntity> entities);
}
