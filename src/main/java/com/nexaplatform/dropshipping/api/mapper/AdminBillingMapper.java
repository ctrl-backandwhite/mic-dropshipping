package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.in.AdminPlanUpdateDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.AdminPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.AdminSubscriptionDtoOut;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.NullValuePropertyMappingStrategy;

import java.util.List;

/**
 * API-layer mapper for the Admin Billing boundary. Flipped to translate between
 * the {@link CustomerSubscription}/{@link SubscriptionPlan} domain models and the
 * transport DTOs (injected in the controller). The admin subscription view reads
 * the computed fields the model carries ({@code userEmail}, {@code planCode},
 * {@code priceMonthly}/{@code priceYearly}); the plan-update builds a partial
 * {@link SubscriptionPlan} the use case applies (null fields ignored so they do
 * not overwrite existing values).
 */
@Mapper(componentModel = "spring", nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
public interface AdminBillingMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "userEmail", source = "userEmail")
    @Mapping(target = "plan", source = "planCode")
    @Mapping(target = "status", expression = "java(model.getStatus() != null ? model.getStatus().name() : null)")
    @Mapping(target = "billingPeriod", source = "billingPeriod")
    @Mapping(target = "currentPeriodStart", source = "currentPeriodStart")
    @Mapping(target = "currentPeriodEnd", source = "currentPeriodEnd")
    @Mapping(target = "priceMonthly", source = "priceMonthly")
    @Mapping(target = "priceYearly", source = "priceYearly")
    AdminSubscriptionDtoOut toSubscriptionDto(CustomerSubscription model);

    List<AdminSubscriptionDtoOut> toSubscriptionDtos(List<CustomerSubscription> models);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "priceMonthlyCents", source = "priceMonthlyCents")
    @Mapping(target = "priceYearlyCents", source = "priceYearlyCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "active", source = "active")
    @Mapping(target = "position", source = "position")
    AdminPlanDtoOut toPlanDto(SubscriptionPlan model);

    List<AdminPlanDtoOut> toPlanDtos(List<SubscriptionPlan> models);

    /**
     * Builds a partial {@link SubscriptionPlan} from the admin update payload. Only
     * the editable fields are carried; identity/audit and the non-editable columns
     * (code/currency/position/stripe ids) are left null so the update mapper applied
     * by the use case does not touch them, preserving the legacy {@code updatePlan}
     * contract.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "code", ignore = true)
    @Mapping(target = "currency", ignore = true)
    @Mapping(target = "position", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "priceMonthlyCents", source = "priceMonthlyCents")
    @Mapping(target = "priceYearlyCents", source = "priceYearlyCents")
    @Mapping(target = "active", source = "active")
    SubscriptionPlan toPlanChanges(AdminPlanUpdateDtoIn dto);
}
