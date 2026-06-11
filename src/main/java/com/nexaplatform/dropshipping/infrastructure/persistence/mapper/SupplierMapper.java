package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.SupplierView;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface SupplierMapper {
    SupplierView toView(SupplierEntity entity);
}
