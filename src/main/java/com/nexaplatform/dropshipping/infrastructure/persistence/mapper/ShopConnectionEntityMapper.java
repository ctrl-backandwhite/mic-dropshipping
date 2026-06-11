package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link ShopConnection} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code user} relation is resolved by the repository adapter (which owns the
 * managed entity), so it is ignored on {@code toEntity}; the domain side carries
 * the flattened {@code userId}. {@code listings} is a read-only count filled by
 * the use case and has no entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface ShopConnectionEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "platform", source = "platform")
    @Mapping(target = "shopHandle", source = "shopHandle")
    @Mapping(target = "accessTokenEnc", source = "accessTokenEnc")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "lastSyncAt", source = "lastSyncAt")
    @Mapping(target = "metadata", source = "metadata")
    @Mapping(target = "listings", ignore = true)
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    ShopConnection toDomain(ShopConnectionEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "platform", source = "platform")
    @Mapping(target = "shopHandle", source = "shopHandle")
    @Mapping(target = "accessTokenEnc", source = "accessTokenEnc")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "lastSyncAt", source = "lastSyncAt")
    @Mapping(target = "metadata", source = "metadata")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    ShopConnectionEntity toEntity(ShopConnection model);

    List<ShopConnection> toDomainList(List<ShopConnectionEntity> entities);
}
