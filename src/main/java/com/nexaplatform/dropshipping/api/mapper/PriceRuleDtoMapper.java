package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.PriceRuleDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.PriceRuleDtoOut;
import com.nexaplatform.dropshipping.domain.model.PriceRule;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for price rules: translates between the transport DTOs and the
 * {@link PriceRule} domain model. Injected in the controller. The enum scope and
 * marginType are exposed as Strings in the DTOs (frontend contract).
 */
@Mapper(componentModel = "spring")
public interface PriceRuleDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "scope", expression = "java(model.getScope() != null ? model.getScope().name() : null)")
    @Mapping(target = "scopeId", source = "scopeId")
    @Mapping(target = "marginType", expression = "java(model.getMarginType() != null ? model.getMarginType().name() : null)")
    @Mapping(target = "marginValue", source = "marginValue")
    @Mapping(target = "minCostUsd", source = "minCostUsd")
    @Mapping(target = "maxCostUsd", source = "maxCostUsd")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "description", source = "description")
    PriceRuleDtoOut toDtoOut(PriceRule model);

    List<PriceRuleDtoOut> toDtoOutList(List<PriceRule> models);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "scope", expression = "java(com.nexaplatform.dropshipping.domain.enums.PriceRuleScope.valueOf(dtoIn.getScope()))")
    @Mapping(target = "scopeId", source = "scopeId")
    @Mapping(target = "marginType", expression = "java(com.nexaplatform.dropshipping.domain.enums.MarginType.valueOf(dtoIn.getMarginType()))")
    @Mapping(target = "marginValue", source = "marginValue")
    @Mapping(target = "minCostUsd", source = "minCostUsd")
    @Mapping(target = "maxCostUsd", source = "maxCostUsd")
    @Mapping(target = "active", expression = "java(dtoIn.getActive() == null || dtoIn.getActive())")
    @Mapping(target = "position", expression = "java(dtoIn.getPosition() == null ? 0 : dtoIn.getPosition())")
    @Mapping(target = "description", source = "description")
    PriceRule toDomain(PriceRuleDtoIn dtoIn);
}
