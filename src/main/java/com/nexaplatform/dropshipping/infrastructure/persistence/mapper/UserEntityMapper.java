package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.PasswordResetToken;
import com.nexaplatform.dropshipping.domain.model.TotpSecret;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PasswordResetTokenEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.TotpSecretEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

/**
 * Infrastructure-layer mapper between the {@link User} domain model and the JPA
 * entity. Builder disabled so MapStruct uses setters and can reach the id/audit
 * fields inherited from {@code BaseEntity}/{@code AuditableEntity}. The nested
 * {@code totp} / {@code resetTokens} relations are resolved by the repository
 * adapter (which owns the managed entities and their secondary tables), so they
 * are ignored on {@code toEntity}; the {@code totpEnabled} computed read field
 * is filled by the use case and has no entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface UserEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "passwordHash", source = "passwordHash")
    @Mapping(target = "role", source = "role")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "activationCode", source = "activationCode")
    @Mapping(target = "activationCodeExpiresAt", source = "activationCodeExpiresAt")
    @Mapping(target = "failedLoginCount", source = "failedLoginCount")
    @Mapping(target = "lockedUntil", source = "lockedUntil")
    @Mapping(target = "lastLogin", source = "lastLogin")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "companyName", source = "companyName")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "avatarUrl", source = "avatarUrl")
    @Mapping(target = "language", source = "language")
    @Mapping(target = "googleLinked", source = "googleLinked")
    @Mapping(target = "totp", ignore = true)
    @Mapping(target = "resetTokens", ignore = true)
    @Mapping(target = "totpEnabled", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    User toDomain(UserEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "email", source = "email")
    @Mapping(target = "passwordHash", source = "passwordHash")
    @Mapping(target = "role", source = "role")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "activationCode", source = "activationCode")
    @Mapping(target = "activationCodeExpiresAt", source = "activationCodeExpiresAt")
    @Mapping(target = "failedLoginCount", source = "failedLoginCount")
    @Mapping(target = "lockedUntil", source = "lockedUntil")
    @Mapping(target = "lastLogin", source = "lastLogin")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "companyName", source = "companyName")
    @Mapping(target = "country", source = "country")
    @Mapping(target = "phone", source = "phone")
    @Mapping(target = "avatarUrl", source = "avatarUrl")
    @Mapping(target = "language", source = "language")
    @Mapping(target = "googleLinked", source = "googleLinked")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    UserEntity toEntity(User model);

    /**
     * Aplica los campos escalares seguros del modelo sobre una entidad gestionada (ruta de
     * actualización). MapStruct auto-mapea el resto por nombre. Se ignoran: id y auditoría
     * ({@code createdAt}/{@code updatedAt}/{@code createdBy}/{@code updatedBy}), que resuelve
     * el auditing de JPA; {@code marketingOptOut}, que no tiene contraparte en el modelo y lo
     * gestiona su propio flujo; y los campos sensibles {@code passwordHash} y {@code role}, con
     * manejo especial en el repositorio (no se deben sobreescribir con null en update).
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "passwordHash", ignore = true)
    @Mapping(target = "role", ignore = true)
    @Mapping(target = "marketingOptOut", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget UserEntity entity, User model);

    /** Maps a TOTP secret entity into its nested model (used when carrying 2FA data). */
    @Named("totpToModel")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "secretEnc", source = "secretEnc")
    @Mapping(target = "enabled", source = "enabled")
    @Mapping(target = "lastUsedAt", source = "lastUsedAt")
    @Mapping(target = "recoveryCodesHash", source = "recoveryCodesHash")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    TotpSecret toTotpModel(TotpSecretEntity entity);

    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "secretEnc", source = "secretEnc")
    @Mapping(target = "enabled", source = "enabled")
    @Mapping(target = "lastUsedAt", source = "lastUsedAt")
    @Mapping(target = "recoveryCodesHash", source = "recoveryCodesHash")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    TotpSecretEntity toTotpEntity(TotpSecret model);

    /** Flattens a password-reset token entity into its nested model (owner id only). */
    @Named("resetTokenToModel")
    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "tokenHash", source = "tokenHash")
    @Mapping(target = "expiresAt", source = "expiresAt")
    @Mapping(target = "consumedAt", source = "consumedAt")
    @Mapping(target = "createdAt", source = "createdAt")
    PasswordResetToken toResetTokenModel(PasswordResetTokenEntity entity);
}
