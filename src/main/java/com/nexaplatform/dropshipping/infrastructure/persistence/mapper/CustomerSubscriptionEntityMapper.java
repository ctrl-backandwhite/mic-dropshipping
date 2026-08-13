package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import java.util.List;

/**
 * Infrastructure-layer mapper between the {@link CustomerSubscription} domain
 * model and the JPA entity. Builder disabled so MapStruct uses setters and can
 * reach the id/audit fields inherited from {@code BaseEntity}/{@code AuditableEntity}.
 *
 * <p>The managed {@code user}/{@code plan} relations are owned by the repository
 * adapter (which resolves them from the flattened {@code userId}/{@code planId}),
 * so they are ignored on {@code toEntity}; on {@code toDomain} they are flattened
 * back into ids plus the computed read-only fields ({@code planCode},
 * {@code userEmail}, {@code priceMonthly}, {@code priceYearly}).
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface CustomerSubscriptionEntityMapper {

    @Mapping(target = "id", source = "id")
    @Mapping(target = "userId", expression = "java(entity.getUser() != null ? entity.getUser().getId() : null)")
    @Mapping(target = "planId", expression = "java(entity.getPlan() != null ? entity.getPlan().getId() : null)")
    @Mapping(target = "status", source = "status")
    @Mapping(target = "billingPeriod", source = "billingPeriod")
    @Mapping(target = "stripeCustomerId", source = "stripeCustomerId")
    @Mapping(target = "stripeSubscriptionId", source = "stripeSubscriptionId")
    @Mapping(target = "currentPeriodStart", source = "currentPeriodStart")
    @Mapping(target = "currentPeriodEnd", source = "currentPeriodEnd")
    @Mapping(target = "cancelAt", source = "cancelAt")
    @Mapping(target = "canceledAt", source = "canceledAt")
    @Mapping(target = "trialEndsAt", source = "trialEndsAt")
    @Mapping(target = "pendingPlanCode", source = "pendingPlanCode")
    @Mapping(target = "pendingPlanAt", source = "pendingPlanAt")
    @Mapping(target = "planCode", expression = "java(entity.getPlan() != null ? entity.getPlan().getCode() : null)")
    @Mapping(target = "userEmail", expression = "java(entity.getUser() != null ? entity.getUser().getEmail() : null)")
    @Mapping(target = "priceMonthly", expression = "java(entity.getPlan() != null ? entity.getPlan().getPriceMonthlyCents() : 0)")
    @Mapping(target = "priceYearly", expression = "java(entity.getPlan() != null ? entity.getPlan().getPriceYearlyCents() : 0)")
    @Mapping(target = "createdAt", source = "createdAt")
    @Mapping(target = "updatedAt", source = "updatedAt")
    @Mapping(target = "createdBy", source = "createdBy")
    @Mapping(target = "updatedBy", source = "updatedBy")
    CustomerSubscription toDomain(CustomerSubscriptionEntity entity);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "plan", ignore = true)
    @Mapping(target = "status", source = "status")
    @Mapping(target = "billingPeriod", source = "billingPeriod")
    @Mapping(target = "stripeCustomerId", source = "stripeCustomerId")
    @Mapping(target = "stripeSubscriptionId", source = "stripeSubscriptionId")
    @Mapping(target = "currentPeriodStart", source = "currentPeriodStart")
    @Mapping(target = "currentPeriodEnd", source = "currentPeriodEnd")
    @Mapping(target = "cancelAt", source = "cancelAt")
    @Mapping(target = "canceledAt", source = "canceledAt")
    @Mapping(target = "trialEndsAt", source = "trialEndsAt")
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    CustomerSubscriptionEntity toEntity(CustomerSubscription model);

    /**
     * Aplica los campos escalares del modelo sobre una entidad gestionada (ruta de actualización).
     * Las relaciones gestionadas ({@code user}, {@code plan}) y la auditoría las resuelve el
     * repositorio, por eso se ignoran aquí. MapStruct auto-mapea el resto por nombre.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "user", ignore = true)
    @Mapping(target = "plan", ignore = true)
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "updatedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "updatedBy", ignore = true)
    void updateEntity(@MappingTarget CustomerSubscriptionEntity entity, CustomerSubscription model);

    List<CustomerSubscription> toDomainList(List<CustomerSubscriptionEntity> entities);
}
