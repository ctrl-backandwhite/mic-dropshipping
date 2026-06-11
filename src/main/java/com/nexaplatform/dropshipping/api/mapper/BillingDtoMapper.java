package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.BillingPlanDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.SubscribeDtoOut;
import com.nexaplatform.dropshipping.domain.model.SubscribeResult;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PlanFeatureEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SubscriptionPlanEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API-layer mapper for the storefront billing endpoints. The subscribe response
 * is projected from the {@link SubscribeResult} domain model. The public plan
 * projection is kept on the {@link SubscriptionPlanEntity} because the
 * frontend-facing {@code limits} map is derived from the plan's feature rows,
 * which the migrated {@code SubscriptionPlan} domain model does not carry;
 * preserving that JSON is a hard requirement, so the use case sources the public
 * list from the plan entity directly. Injected in the controller.
 */
@Mapper(componentModel = "spring")
public interface BillingDtoMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "code", source = "code")
    @Mapping(target = "name", source = "name")
    @Mapping(target = "description", source = "description")
    @Mapping(target = "priceMonthlyCents", source = "priceMonthlyCents")
    @Mapping(target = "priceYearlyCents", source = "priceYearlyCents")
    @Mapping(target = "currency", source = "currency")
    @Mapping(target = "position", source = "position")
    @Mapping(target = "limits", source = "features", qualifiedByName = "featuresToLimits")
    BillingPlanDtoOut toPlanDtoOut(SubscriptionPlanEntity plan);

    List<BillingPlanDtoOut> toPlanDtoOutList(List<SubscriptionPlanEntity> plans);

    /**
     * Builds the subscribe response from the resolved checkout URL and session id.
     * Used both in dev mode (local subscription id) and provider checkout mode.
     */
    @Mapping(target = "checkoutUrl", source = "checkoutUrl")
    @Mapping(target = "sessionId", source = "sessionId")
    SubscribeDtoOut toSubscribeDtoOut(SubscribeResult result);

    @Named("featuresToLimits")
    default Map<String, Long> featuresToLimits(List<PlanFeatureEntity> features) {
        if (features == null) {
            return Map.of();
        }
        return features.stream()
                .filter(f -> f.getIntValue() != null)
                .collect(Collectors.toMap(PlanFeatureEntity::getFeatureKey, PlanFeatureEntity::getIntValue, (a, b) -> a));
    }
}
