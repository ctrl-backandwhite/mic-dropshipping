package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.CurrencyDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper: translates the currency rate entity into its transport DTO.
 * Every field is mapped explicitly, following the project mapper pattern.
 */
@Mapper(componentModel = "spring")
public interface CurrencyDtoMapper {

    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "symbol", source = "symbol")
    @Mapping(target = "countryCode", source = "countryCode")
    @Mapping(target = "flagEmoji", source = "flagEmoji")
    @Mapping(target = "locale", source = "locale")
    @Mapping(target = "rateVsUsd", source = "rateVsUsd")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "lastSyncedAt", source = "lastSyncedAt")
    CurrencyDtoOut toDtoOut(CurrencyRateEntity entity);

    List<CurrencyDtoOut> toDtoOutList(List<CurrencyRateEntity> entities);
}
