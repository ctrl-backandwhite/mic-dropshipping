package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.AdTrend;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AdTrendEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link AdTrend} domain model and the
 * JPA entity. Builder disabled so MapStruct uses setters and can reach the
 * generated id. {@code AdTrendEntity} has no audit columns, so only the
 * domain-visible fields are mapped explicitly.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface AdTrendEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "headline", source = "headline")
    @Mapping(target = "productSlug", source = "productSlug")
    @Mapping(target = "impressions", source = "impressions")
    @Mapping(target = "engagement", source = "engagement")
    @Mapping(target = "score", source = "score")
    @Mapping(target = "region", source = "region")
    @Mapping(target = "capturedAt", source = "capturedAt")
    AdTrend toDomain(AdTrendEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "source", source = "source")
    @Mapping(target = "headline", source = "headline")
    @Mapping(target = "productSlug", source = "productSlug")
    @Mapping(target = "impressions", source = "impressions")
    @Mapping(target = "engagement", source = "engagement")
    @Mapping(target = "score", source = "score")
    @Mapping(target = "region", source = "region")
    @Mapping(target = "capturedAt", source = "capturedAt")
    AdTrendEntity toEntity(AdTrend model);

    List<AdTrend> toDomainList(List<AdTrendEntity> entities);
}
