package com.nexaplatform.dropshipping.application.mapper;

import com.nexaplatform.dropshipping.domain.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * Applies a partial update of one {@link User} onto an existing one, preserving
 * identity/audit and the security-sensitive fields (password, role, lock state,
 * activation). Null source properties are ignored so only the present fields
 * change. Used by the profile/admin inline-edit flows in the use case.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface UserUpdateMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "email", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "activationCode", ignore = true)
    @Mapping(target = "activationCodeExpiresAt", ignore = true)
    @Mapping(target = "failedLoginCount", ignore = true)
    @Mapping(target = "lockedUntil", ignore = true)
    @Mapping(target = "lastLogin", ignore = true)
    @Mapping(target = "avatarUrl", ignore = true)
    @Mapping(target = "freeTrialUsed", ignore = true)
    @Mapping(target = "totp", ignore = true)
    @Mapping(target = "resetTokens", ignore = true)
    @Mapping(target = "totpEnabled", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateFromModel(User source, @MappingTarget User target);
}
