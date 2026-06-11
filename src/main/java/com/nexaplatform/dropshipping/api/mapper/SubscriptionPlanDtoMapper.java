package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.SubscriptionPlanDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.SubscriptionPlanDtoOut;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

/**
 * API-layer mapper: translates between the transport DTOs and the domain model.
 * Used by the controller (DtoIn -> domain on the way in, domain -> DtoOut out).
 * Every field is mapped explicitly, following the project mapper pattern.
 */
@Mapper(componentModel = "spring")
public interface SubscriptionPlanDtoMapper {

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
    SubscriptionPlanDtoOut toDtoOut(SubscriptionPlan model);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "priceMonthlyCents", source = "priceMonthlyCents")
    @Mapping(target = "priceYearlyCents", source = "priceYearlyCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "position", source = "position")
    SubscriptionPlan toDomain(SubscriptionPlanDtoIn dtoIn);

    List<SubscriptionPlan> toDomainList(List<SubscriptionPlanDtoIn> dtos);

    List<SubscriptionPlanDtoOut> toDtoOutList(List<SubscriptionPlan> models);
}
