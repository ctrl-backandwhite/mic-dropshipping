package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.PriceRuleDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PriceRuleDtoOut;
import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * API-layer mapper for price rules. Translates the JPA {@link PriceRuleEntity}
 * to the transport {@link PriceRuleDtoOut} and applies a {@link PriceRuleDtoIn}
 * onto an entity. Every field is mapped explicitly. Replaces the hand-written
 * {@code toView}/{@code fromRequest} helpers that used to live in the controller.
 */
@Mapper(componentModel = "spring")
public interface PriceRuleDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "scope", expression = "java(entity.getScope() != null ? entity.getScope().name() : null)")
    @Mapping(target = "scopeId", source = "scopeId")
    @Mapping(target = "marginType", expression = "java(entity.getMarginType() != null ? entity.getMarginType().name() : null)")
    @Mapping(target = "marginValue", source = "marginValue")
    @Mapping(target = "minCostUsd", source = "minCostUsd")
    @Mapping(target = "maxCostUsd", source = "maxCostUsd")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "description", source = "description")
    PriceRuleDtoOut toDtoOut(PriceRuleEntity entity);

    List<PriceRuleDtoOut> toDtoOutList(List<PriceRuleEntity> entities);

    /**
     * Applies the request fields onto the target entity. Inherited id/audit
     * fields (from {@code BaseEntity}) are left untouched. {@code active} and
     * {@code position} fall back to their legacy defaults (true / 0) when null.
     */
    @Mapping(target = "scope", expression = "java(com.nexaplatform.dropshipping.domain.enums.PriceRuleScope.valueOf(dtoIn.getScope()))")
    @Mapping(target = "scopeId", source = "scopeId")
    @Mapping(target = "marginType", expression = "java(com.nexaplatform.dropshipping.domain.enums.MarginType.valueOf(dtoIn.getMarginType()))")
    @Mapping(target = "marginValue", source = "marginValue")
    @Mapping(target = "minCostUsd", source = "minCostUsd")
    @Mapping(target = "maxCostUsd", source = "maxCostUsd")
    @Mapping(target = "active", expression = "java(dtoIn.getActive() == null || dtoIn.getActive())")
    @Mapping(target = "position", expression = "java(dtoIn.getPosition() == null ? 0 : dtoIn.getPosition())")
    @Mapping(target = "description", source = "description")
    void applyToEntity(PriceRuleDtoIn dtoIn, @MappingTarget PriceRuleEntity target);
}
