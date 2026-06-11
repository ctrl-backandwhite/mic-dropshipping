package com.nexaplatform.dropshipping.application.mapper;

import com.nexaplatform.dropshipping.domain.model.Category;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

/**
 * Applies a partial update of one {@link Category} onto an existing one,
 * preserving identity/audit and the read-only {@code productCount}. Null source
 * properties are ignored so unset DtoIn fields keep their current value
 * (mirrors the legacy {@code if (req.getX() != null)} guards). {@code parentId},
 * {@code position} and {@code active} are reconciled by the use case because
 * {@code parentId} is reconciled by the use case because its semantics are
 * unconditional (a null clears the parent) rather than null-ignored.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface CategoryUpdateMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "source", ignore = true)
    @Mapping(target = "externalId", ignore = true)
    @Mapping(target = "parentId", ignore = true)
    @Mapping(target = "productCount", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateFromModel(Category source, @MappingTarget Category target);
}
