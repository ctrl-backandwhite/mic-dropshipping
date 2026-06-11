package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * Infrastructure-layer mapper: translates between the domain model and the JPA
 * entity, with every field mapped explicitly. Audit fields and persistence-only
 * columns are left to JPA/auditing. Builder disabled so MapStruct uses setters
 * and can reach the id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface SubscriptionPlanEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "priceMonthlyCents", source = "priceMonthlyCents")
    @Mapping(target = "priceYearlyCents", source = "priceYearlyCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    SubscriptionPlan toDomain(SubscriptionPlanEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "priceMonthlyCents", source = "priceMonthlyCents")
    @Mapping(target = "priceYearlyCents", source = "priceYearlyCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "stripeMonthlyPriceId", ignore = true)
    @Mapping(target = "stripeYearlyPriceId", ignore = true)
    @Mapping(target = "features", ignore = true)
    SubscriptionPlanEntity toEntity(SubscriptionPlan model);

    List<SubscriptionPlan> toDomainList(List<SubscriptionPlanEntity> entities);

    List<SubscriptionPlanEntity> toEntityList(List<SubscriptionPlan> models);
}
