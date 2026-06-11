package com.nexaplatform.dropshipping.application.mapper;

import com.nexaplatform.dropshipping.domain.model.UserAddress;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Applies a partial update of one {@link UserAddress} onto an existing one,
 * preserving identity, ownership, audit and the default flag. The default flag
 * and ownership are managed by the use case (single-default invariant), so they
 * are intentionally ignored here.
 */
@Mapper(componentModel = "spring")
public interface UserAddressUpdateMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "default", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateFromModel(UserAddress source, @MappingTarget UserAddress target);
}
