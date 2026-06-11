package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.AuthDtos.RegisterRequest;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.Set;

/**
 * API-layer mapper for the authenticated user / auth endpoints. Maps the user
 * entity into its {@link MeDtoOut} transport view and the {@link RegisterDtoIn}
 * payload into the {@link RegisterRequest} the auth service consumes. Every
 * field is mapped explicitly, following the project mapper pattern.
 */
@Mapper(componentModel = "spring")
public interface UserDtoMapper {

    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "role", source = "user.role")
    @Mapping(target = "active", source = "user.active")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "companyName", source = "user.companyName")
    @Mapping(target = "country", source = "user.country")
    @Mapping(target = "language", source = "user.language")
    @Mapping(target = "avatarUrl", source = "user.avatarUrl")
    @Mapping(target = "createdAt", source = "user.createdAt")
    @Mapping(target = "lastLogin", source = "user.lastLogin")
    @Mapping(target = "authorities", source = "authorities")
    MeDtoOut toMeDtoOut(UserEntity user, Set<String> authorities);

    @Mapping(target = "email", source = "email")
    @Mapping(target = "password", source = "password")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "companyName", source = "companyName")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "language", source = "language")
    RegisterRequest toRegisterRequest(RegisterDtoIn dtoIn);
}
