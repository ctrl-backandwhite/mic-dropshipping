package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.TrendRowDtoOut;
import com.nexaplatform.dropshipping.domain.model.AdTrend;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper for ad trends: translates the {@link AdTrend} domain model
 * into the transport DTO. Injected in the controller. DtoOut field names mirror
 * the legacy {@code TrendRow} record (id, source, headline, productSlug,
 * impressions, engagement, score, region, capturedAt).
 */
@Mapper(componentModel = "spring")
public interface AdTrendDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "headline", source = "headline")
    @Mapping(target = "productSlug", source = "productSlug")
    @Mapping(target = "impressions", source = "impressions")
    @Mapping(target = "engagement", source = "engagement")
    @Mapping(target = "score", source = "score")
    @Mapping(target = "region", source = "region")
    @Mapping(target = "capturedAt", source = "capturedAt")
    TrendRowDtoOut toDtoOut(AdTrend model);

    List<TrendRowDtoOut> toDtoOutList(List<AdTrend> models);
}
