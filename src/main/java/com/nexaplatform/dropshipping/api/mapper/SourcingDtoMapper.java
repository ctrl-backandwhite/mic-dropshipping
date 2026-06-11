package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentLiteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingQuoteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingRequestDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AgentProfileEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingQuoteEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingRequestEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * MapStruct mapper for the Sourcing API boundary. Translates JPA entities into
 * transport DtoOut classes, preserving the exact JSON field names of the
 * previous {@code RequestView}/{@code QuoteView}/{@code AgentLite}/{@code AgentFull}
 * records. The quotes count is computed outside the mapper and supplied as an
 * argument.
 */
@Mapper(componentModel = "spring")
public interface SourcingDtoMapper {

    @Mapping(target = "id", source = "request.id")
    @Mapping(target = "sourceUrl", source = "request.sourceUrl")
    @Mapping(target = "source", source = "request.source")
    @Mapping(target = "externalId", source = "request.externalId")
    @Mapping(target = "status", source = "request.status")
    @Mapping(target = "titleHint", source = "request.titleHint")
    @Mapping(target = "notes", source = "request.notes")
    @Mapping(target = "selectedQuoteId", source = "request.selectedQuoteId")
    @Mapping(target = "createdAt", source = "request.createdAt")
    @Mapping(target = "quotesCount", source = "quotesCount")
    SourcingRequestDtoOut toRequest(SourcingRequestEntity request, int quotesCount);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "requestId", source = "request.id")
    @Mapping(target = "agent", source = "agent")
    @Mapping(target = "priceUsdCents", source = "priceUsdCents")
    @Mapping(target = "etaDays", source = "etaDays")
    @Mapping(target = "moq", source = "moq")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    SourcingQuoteDtoOut toQuote(SourcingQuoteEntity quote);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "tier", source = "tier")
    @Mapping(target = "satisfaction", source = "satisfaction")
    @Mapping(target = "completedJobs", source = "completedJobs")
    @Mapping(target = "avatarUrl", source = "avatarUrl")
    SourcingAgentLiteDtoOut toAgentLite(AgentProfileEntity agent);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "tier", source = "tier")
    @Mapping(target = "bio", source = "bio")
    @Mapping(target = "avatarUrl", source = "avatarUrl")
    @Mapping(target = "languages", source = "languages")
    @Mapping(target = "successRate", source = "successRate")
    @Mapping(target = "avgResponseHours", source = "avgResponseHours")
    @Mapping(target = "satisfaction", source = "satisfaction")
    @Mapping(target = "completedJobs", source = "completedJobs")
    @Mapping(target = "hourlyRateUsdCents", source = "hourlyRateUsdCents")
    SourcingAgentDetailDtoOut toAgentDetail(AgentProfileEntity agent);
}
