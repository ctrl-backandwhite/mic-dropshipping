package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.ShopProductListing;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link ShopProductListing} domain model
 * and the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code shopConnection} and {@code product} relations are resolved by the
 * repository adapter (which owns the managed entity), so they are ignored on
 * {@code toEntity}; the domain side carries the flattened {@code shopConnectionId}
 * and {@code productId}. {@code productTitle} is a read-only field flattened from
 * the related product on {@code toDomain}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface ShopProductListingEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "shopConnectionId", expression = "java(entity.getShopConnection() != null ? entity.getShopConnection().getId() : null)")
    @Mapping(target = "productId", expression = "java(entity.getProduct() != null ? entity.getProduct().getId() : null)")
    @Mapping(target = "productTitle", expression = "java(entity.getProduct() != null ? entity.getProduct().getTitleZh() : null)")
    @Mapping(target = "remoteProductId", source = "remoteProductId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "lastPushedAt", source = "lastPushedAt")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    ShopProductListing toDomain(ShopProductListingEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "shopConnection", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "remoteProductId", source = "remoteProductId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "lastPushedAt", source = "lastPushedAt")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    ShopProductListingEntity toEntity(ShopProductListing model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización).
     * Las relaciones gestionadas ({@code shopConnection}, {@code product}) y la auditoría las resuelve
     * el repositorio, por eso se ignoran aquí. MapStruct auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "shopConnection", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget ShopProductListingEntity entity, ShopProductListing model);

    List<ShopProductListing> toDomainList(List<ShopProductListingEntity> entities);
}
