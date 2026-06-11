package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminDashboardRecentOrderDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import org.mapstruct.Mapper;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * MapStruct mapper for the Admin Dashboard API boundary.
 * Converts JPA order entities into recent-order DTOs.
 * Combined with Lombok: entity getters and DtoOut builders are Lombok-generated
 * and consumed by the MapStruct-generated implementation. The {@code status} enum
 * is mapped to its {@code name()} string automatically by MapStruct.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AdminDashboardMapper {

    AdminDashboardRecentOrderDtoOut toRecentOrderDto(CustomerOrderEntity entity);

    List<AdminDashboardRecentOrderDtoOut> toRecentOrderDtos(List<CustomerOrderEntity> entities);
}
