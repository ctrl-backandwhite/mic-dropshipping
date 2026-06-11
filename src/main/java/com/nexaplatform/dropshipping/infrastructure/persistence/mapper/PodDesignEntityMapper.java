package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.PodDesign;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PodDesignEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link PodDesign} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 * The {@code user} and {@code product} relations are resolved by the repository
 * adapter (which owns the managed entity), so they are ignored on
 * {@code toEntity}; the domain side carries the flattened {@code userId} /
 * {@code productId}. {@code productTitle} is a read-only field flattened from the
 * related product and has no settable entity counterpart.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface PodDesignEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "user.id")
    @Mapping(target = "productId", source = "product.id")
    @Mapping(target = "productTitle", source = "product.titleZh")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "canvasJson", source = "canvasJson")
    @Mapping(target = "mockupUrl", source = "mockupUrl")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "aiPrompt", source = "aiPrompt")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    PodDesign toDomain(PodDesignEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "product", ignore = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "canvasJson", source = "canvasJson")
    @Mapping(target = "mockupUrl", source = "mockupUrl")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "aiPrompt", source = "aiPrompt")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    PodDesignEntity toEntity(PodDesign model);

    List<PodDesign> toDomainList(List<PodDesignEntity> entities);
}
