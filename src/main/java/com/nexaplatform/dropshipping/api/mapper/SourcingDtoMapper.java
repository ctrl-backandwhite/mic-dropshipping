package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentDetailDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingAgentLiteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingQuoteDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SourcingRequestDtoOut;
import com.nexaplatform.dropshipping.domain.model.SourcingAgent;
import com.nexaplatform.dropshipping.domain.model.SourcingQuote;
import com.nexaplatform.dropshipping.domain.model.SourcingRequest;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for the Sourcing resource: translates the domain models
 * ({@link SourcingRequest}, {@link SourcingQuote}, {@link SourcingAgent}) into the
 * transport DtoOut classes, preserving the exact JSON field names of the
 * {@code RequestView}/{@code QuoteView}/{@code AgentLite}/{@code AgentFull}
 * contract. Injected in the controller.
 */
@Mapper(componentModel = "spring")
public interface SourcingDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "sourceUrl", source = "sourceUrl")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "externalId", source = "externalId")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "titleHint", source = "titleHint")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "selectedQuoteId", source = "selectedQuoteId")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "quotesCount", expression = "java((int) model.getQuotesCount())")
    SourcingRequestDtoOut toRequest(SourcingRequest model);

    List<SourcingRequestDtoOut> toRequestList(List<SourcingRequest> models);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "requestId", source = "requestId")
    @Mapping(target = "agent", source = "agent")
    @Mapping(target = "priceUsdCents", source = "priceUsdCents")
    @Mapping(target = "etaDays", source = "etaDays")
    @Mapping(target = "moq", source = "moq")
    @Mapping(target = "notes", source = "notes")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "createdAt", source = "createdAt")
    SourcingQuoteDtoOut toQuote(SourcingQuote model);

    List<SourcingQuoteDtoOut> toQuoteList(List<SourcingQuote> models);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "displayName", source = "displayName")
    @Mapping(target = "tier", source = "tier")
    @Mapping(target = "satisfaction", source = "satisfaction")
    @Mapping(target = "completedJobs", source = "completedJobs")
    @Mapping(target = "avatarUrl", source = "avatarUrl")
    SourcingAgentLiteDtoOut toAgentLite(SourcingAgent model);

    List<SourcingAgentLiteDtoOut> toAgentLiteList(List<SourcingAgent> models);

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
    SourcingAgentDetailDtoOut toAgentDetail(SourcingAgent model);
}
