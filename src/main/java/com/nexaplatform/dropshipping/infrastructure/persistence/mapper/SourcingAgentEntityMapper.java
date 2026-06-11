package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.SourcingAgent;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AgentProfileEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link SourcingAgent} domain model and
 * the {@code AgentProfileEntity}. Builder disabled so MapStruct uses setters and
 * can reach the id/audit fields inherited from {@code BaseEntity}/{@code
 * AuditableEntity}. The {@code user} relation is persistence-only and ignored on
 * {@code toEntity}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface SourcingAgentEntityMapper {

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
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    SourcingAgent toDomain(AgentProfileEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
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
    @Mapping(target = "active", source = "active")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    AgentProfileEntity toEntity(SourcingAgent model);

    List<SourcingAgent> toDomainList(List<AgentProfileEntity> entities);
}
