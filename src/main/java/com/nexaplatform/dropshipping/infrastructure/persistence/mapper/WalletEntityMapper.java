package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link Wallet} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The managed {@code user} relation is resolved by the repository adapter from
 * the flattened {@code userId}, so it is ignored on {@code toEntity}; the
 * {@code userEmail}/{@code userName} fields are read-only enrichment filled by
 * the use case and have no entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface WalletEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "balanceUsdCents", source = "balanceUsdCents")
    @Mapping(target = "holdUsdCents", source = "holdUsdCents")
    @Mapping(target = "currencyDefault", source = "currencyDefault")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "userEmail", expression = "java(entity.getUser() != null ? entity.getUser().getEmail() : null)")
    @Mapping(target = "userName", expression = "java(entity.getUser() != null ? entity.getUser().getDisplayName() : null)")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    Wallet toDomain(WalletEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "balanceUsdCents", source = "balanceUsdCents")
    @Mapping(target = "holdUsdCents", source = "holdUsdCents")
    @Mapping(target = "currencyDefault", source = "currencyDefault")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    WalletEntity toEntity(Wallet model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización).
     * La relación gestionada {@code user} la resuelve el repositorio desde el {@code userId} aplanado,
     * por eso se ignora aquí junto con la auditoría. MapStruct auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget WalletEntity entity, Wallet model);

    List<Wallet> toDomainList(List<WalletEntity> entities);
}
