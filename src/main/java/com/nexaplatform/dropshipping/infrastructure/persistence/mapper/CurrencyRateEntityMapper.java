package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.CurrencyRate;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link CurrencyRate} domain model and
 * the JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface CurrencyRateEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "symbol", source = "symbol")
    @Mapping(target = "countryCode", source = "countryCode")
    @Mapping(target = "flagEmoji", source = "flagEmoji")
    @Mapping(target = "locale", source = "locale")
    @Mapping(target = "rateVsUsd", source = "rateVsUsd")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "lastSyncedAt", source = "lastSyncedAt")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    CurrencyRate toDomain(CurrencyRateEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "symbol", source = "symbol")
    @Mapping(target = "countryCode", source = "countryCode")
    @Mapping(target = "flagEmoji", source = "flagEmoji")
    @Mapping(target = "locale", source = "locale")
    @Mapping(target = "rateVsUsd", source = "rateVsUsd")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "lastSyncedAt", source = "lastSyncedAt")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    CurrencyRateEntity toEntity(CurrencyRate model);

    List<CurrencyRate> toDomainList(List<CurrencyRateEntity> entities);
}
