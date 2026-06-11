package com.nexaplatform.dropshipping.application.mapper;

import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/**
 * Application-layer mapper applying a partial update of one
 * {@link CustomerSubscription} onto an existing one (MapStruct
 * {@code @MappingTarget}), preserving identity and audit.
 */
@Mapper(componentModel = "spring")
public interface CustomerSubscriptionUpdateMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateFromModel(CustomerSubscription source, @MappingTarget CustomerSubscription target);
}
