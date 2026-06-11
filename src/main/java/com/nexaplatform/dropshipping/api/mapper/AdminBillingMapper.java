package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.AdminPlanUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSubscriptionDtoOut;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * MapStruct mapper for the Admin Billing API boundary.
 * Converts JPA entities into DTOs and applies partial updates from DtoIn.
 * Combined with Lombok: entity getters/setters and DtoOut builders are
 * Lombok-generated and consumed by the MapStruct-generated implementation.
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AdminBillingMapper {

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.email", target = "userEmail")
    @Mapping(source = "plan.code", target = "plan")
    @Mapping(source = "plan.priceMonthlyCents", target = "priceMonthly")
    @Mapping(source = "plan.priceYearlyCents", target = "priceYearly")
    AdminSubscriptionDtoOut toSubscriptionDto(CustomerSubscriptionEntity entity);

    List<AdminSubscriptionDtoOut> toSubscriptionDtos(List<CustomerSubscriptionEntity> entities);

    AdminPlanDtoOut toPlanDto(SubscriptionPlanEntity entity);

    List<AdminPlanDtoOut> toPlanDtos(List<SubscriptionPlanEntity> entities);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "position", ignore = true)
    @Mapping(target = "features", ignore = true)
    @Mapping(target = "stripeMonthlyPriceId", ignore = true)
    @Mapping(target = "stripeYearlyPriceId", ignore = true)
    void updatePlanFromDto(AdminPlanUpdateDtoIn dto, @MappingTarget SubscriptionPlanEntity entity);
}
