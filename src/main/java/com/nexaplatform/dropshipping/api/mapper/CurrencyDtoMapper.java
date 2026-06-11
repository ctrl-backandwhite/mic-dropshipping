package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.CurrencyDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CurrencySyncResultDtoOut;
import com.nexaplatform.dropshipping.domain.model.CurrencyRate;
import com.nexaplatform.dropshipping.domain.model.CurrencySyncResult;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for currency rates: translates between the {@link CurrencyRate}
 * domain model and the transport DTOs. Injected in the controller. Every field is
 * mapped explicitly, following the project mapper pattern.
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
    CurrencyDtoOut toDtoOut(CurrencyRate model);

    List<CurrencyDtoOut> toDtoOutList(List<CurrencyRate> models);

    @Mapping(target = "updated", source = "updated")
    @Mapping(target = "message", source = "message")
    CurrencySyncResultDtoOut toDtoOut(CurrencySyncResult model);
}
