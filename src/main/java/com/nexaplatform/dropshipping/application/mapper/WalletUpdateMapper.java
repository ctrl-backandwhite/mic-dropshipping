package com.nexaplatform.dropshipping.application.mapper;

import com.nexaplatform.dropshipping.domain.model.Wallet;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

/** Applies a partial update of one {@link Wallet} onto an existing one, preserving identity/audit. */
@Mapper(componentModel = "spring")
public interface WalletUpdateMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateFromModel(Wallet source, @MappingTarget Wallet target);
}
