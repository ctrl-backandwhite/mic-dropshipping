package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.PriceRule;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link PriceRule} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface PriceRuleEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "scopeId", source = "scopeId")
    @Mapping(target = "marginType", source = "marginType")
    @Mapping(target = "marginValue", source = "marginValue")
    @Mapping(target = "minCostUsd", source = "minCostUsd")
    @Mapping(target = "maxCostUsd", source = "maxCostUsd")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "channel", source = "channel")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    PriceRule toDomain(PriceRuleEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "scope", source = "scope")
    @Mapping(target = "scopeId", source = "scopeId")
    @Mapping(target = "marginType", source = "marginType")
    @Mapping(target = "marginValue", source = "marginValue")
    @Mapping(target = "minCostUsd", source = "minCostUsd")
    @Mapping(target = "maxCostUsd", source = "maxCostUsd")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "description", source = "description")
    // Admin no gestiona el canal: si el modelo no lo trae (reglas creadas desde el admin), por defecto STOREFRONT.
    @Mapping(target = "channel", source = "channel", defaultValue = "STOREFRONT")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    PriceRuleEntity toEntity(PriceRule model);

    List<PriceRule> toDomainList(List<PriceRuleEntity> entities);
}
