package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AdminUserCreatedDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * API-layer mapper translating a created {@link UserEntity} into the
 * {@link AdminUserCreatedDtoOut} transport object. Every field is mapped
 * explicitly. {@code builder = disableBuilder} because the DtoOut is built
 * via constructor and only the id is needed.
 */
@Mapper(componentModel = "spring")
public interface AdminUserCreatedDtoMapper {

    @Mapping(target = "id", source = "id")
    AdminUserCreatedDtoOut toDtoOut(UserEntity entity);
}
