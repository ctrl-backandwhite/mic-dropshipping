package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.CreateAdminUserDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminUserEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.RegisterDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserCreatedDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminUserPageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.MeDtoOut;
import com.nexaplatform.dropshipping.domain.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;
import java.util.Set;

/**
 * API-layer mapper for the Auth/User cluster: translates between the transport
 * DTOs and the {@link User} domain model. Injected in every controller of the
 * cluster. The enum {@code role} is exposed as a String in the DTOs (frontend
 * contract); the model carries it as the {@code UserRole} enum.
 */
@Mapper(componentModel = "spring")
public interface UserDtoMapper {

    /* ============ Model -> DtoOut ============ */

    @Mapping(target = "id", source = "user.id")
    @Mapping(target = "email", source = "user.email")
    @Mapping(target = "role", expression = "java(user.getRole() != null ? user.getRole().name() : null)")
    @Mapping(target = "active", source = "user.active")
    @Mapping(target = "displayName", source = "user.displayName")
    @Mapping(target = "companyName", source = "user.companyName")
    @Mapping(target = "country", source = "user.country")
    @Mapping(target = "language", source = "user.language")
    @Mapping(target = "avatarUrl", source = "user.avatarUrl")
    @Mapping(target = "createdAt", source = "user.createdAt")
    @Mapping(target = "lastLogin", source = "user.lastLogin")
    @Mapping(target = "authorities", source = "authorities")
    MeDtoOut toMeDtoOut(User user, Set<String> authorities);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "role", expression = "java(model.getRole() != null ? model.getRole().name() : null)")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "companyName", source = "companyName")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "language", source = "language")
    @Mapping(target = "lockedUntil", source = "lockedUntil")
    @Mapping(target = "failedLoginCount", source = "failedLoginCount")
    @Mapping(target = "lastLogin", source = "lastLogin")
    @Mapping(target = "createdAt", source = "createdAt")
    AdminUserDtoOut toAdminDtoOut(User model);

    List<AdminUserDtoOut> toAdminDtoOutList(List<User> models);

    @Mapping(target = "id", source = "id")
    AdminUserCreatedDtoOut toCreatedDtoOut(User model);

    /* ============ DtoIn -> Model ============ */

    @Mapping(target = "id", ignore = true)
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
    @Mapping(target = "totp", ignore = true)
    @Mapping(target = "resetTokens", ignore = true)
    @Mapping(target = "totpEnabled", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "email", source = "email")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "companyName", source = "companyName")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "language", source = "language")
    User toDomain(RegisterDtoIn dtoIn);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "active", ignore = true)
    @Mapping(target = "activationCode", ignore = true)
    @Mapping(target = "activationCodeExpiresAt", ignore = true)
    @Mapping(target = "failedLoginCount", ignore = true)
    @Mapping(target = "lockedUntil", ignore = true)
    @Mapping(target = "lastLogin", ignore = true)
    @Mapping(target = "companyName", ignore = true)
    @Mapping(target = "country", ignore = true)
    @Mapping(target = "language", ignore = true)
    @Mapping(target = "phone", ignore = true)
    @Mapping(target = "avatarUrl", ignore = true)
    @Mapping(target = "totp", ignore = true)
    @Mapping(target = "resetTokens", ignore = true)
    @Mapping(target = "totpEnabled", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "email", source = "email")
    @Mapping(target = "displayName", source = "displayName")
    User toDomain(CreateAdminUserDtoIn req);

    /** Builds a sparse {@link User} carrying only the inline-editable fields. */
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
    @Mapping(target = "phone", ignore = true)
    @Mapping(target = "avatarUrl", ignore = true)
    @Mapping(target = "totp", ignore = true)
    @Mapping(target = "resetTokens", ignore = true)
    @Mapping(target = "totpEnabled", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "companyName", source = "companyName")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "language", source = "language")
    User toDomain(AdminUserEditDtoIn dtoIn);

    /** Assembles the paged admin-users response from the page slice + total count. */
    default AdminUserPageDtoOut toAdminPageDtoOut(List<User> pageItems, int total, int page, int size) {
        return AdminUserPageDtoOut.builder().items(toAdminDtoOutList(pageItems)).totalElements(total)
                .totalPages((int) Math.ceil((double) total / Math.max(1, size))).page(page).size(size).build();
    }
}
