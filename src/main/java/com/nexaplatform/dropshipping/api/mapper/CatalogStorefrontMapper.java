package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import org.mapstruct.Mapper;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * MapStruct mapper for the storefront catalog image and price-tier projections.
 * Combined with Lombok: entity getters and DtoOut builders are Lombok-generated
 * and consumed by the MapStruct-generated implementation.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface CatalogStorefrontMapper {

    CatalogImageDtoOut toImageDto(ProductImageEntity entity);

    List<CatalogImageDtoOut> toImageDtos(List<ProductImageEntity> entities);

    CatalogPriceTierDtoOut toPriceTierDto(ProductPriceTierEntity entity);

    List<CatalogPriceTierDtoOut> toPriceTierDtos(List<ProductPriceTierEntity> entities);
}
