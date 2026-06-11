package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.AffiliateDtoOut;
import com.nexaplatform.dropshipping.domain.model.Affiliate;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for affiliate accounts: translates the {@link Affiliate}
 * domain model into the transport DTO. Injected in the controller. DtoOut field
 * names preserve the exact JSON keys the frontend already consumes (mirroring the
 * legacy {@code AffiliateView} record).
 */
@Mapper(componentModel = "spring")
public interface AffiliateDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "earningsUsdCents", source = "earningsUsdCents")
    @Mapping(target = "payoutUsdCents", source = "payoutUsdCents")
    @Mapping(target = "referralsCount", source = "referralsCount")
    @Mapping(target = "active", source = "active")
    AffiliateDtoOut toDtoOut(Affiliate model);

    List<AffiliateDtoOut> toDtoOutList(List<Affiliate> models);
}
