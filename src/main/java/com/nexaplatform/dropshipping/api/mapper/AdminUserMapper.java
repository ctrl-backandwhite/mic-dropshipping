package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * MapStruct mapper for the Admin Users API boundary.
 * Converts JPA entities into DTOs and applies partial updates from DtoIn.
 * Combined with Lombok: entity getters/setters and DtoOut builders are
 * Lombok-generated and consumed by the MapStruct-generated implementation.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AdminUserMapper {

    AdminUserDtoOut toDto(UserEntity entity);

    List<AdminUserDtoOut> toDtos(List<UserEntity> entities);

    /**
     * Applies the inline-edit partial update. The {@code active} flag is a
     * primitive on the entity and is handled in the service to preserve the
     * "only change when present in the body" semantics; here only the
     * string fields are mapped, and null values are ignored (no overwrite).
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "email", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "activationCode", ignore = true)
    @Mapping(target = "activationCodeExpiresAt", ignore = true)
    @Mapping(target = "failedLoginCount", ignore = true)
    @Mapping(target = "lockedUntil", ignore = true)
    @Mapping(target = "lastLogin", ignore = true)
    @Mapping(target = "phone", ignore = true)
    @Mapping(target = "avatarUrl", ignore = true)
    void updateUserFromDto(AdminUserEditDtoIn dto, @MappingTarget UserEntity entity);
}
