package com.nexaplatform.dropshipping.application.mapper;

import com.nexaplatform.dropshipping.domain.model.Payment;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** Applies a partial update of one {@link Payment} onto an existing one, preserving identity/audit. */
@Mapper(componentModel = "spring")
public interface PaymentUpdateMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateFromModel(Payment source, @MappingTarget Payment target);
}
