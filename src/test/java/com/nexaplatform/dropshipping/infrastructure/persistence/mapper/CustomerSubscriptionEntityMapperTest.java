package com.nexaplatform.dropshipping.infrastructure.persistence.mapper;

import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Mapper CustomerSubscription(model) <-> CustomerSubscriptionEntity. El round-trip Model->Entity->Model
 * conserva los campos escalares; las relaciones gestionadas (user/plan) se ignoran en toEntity, por lo
 * que {@code userId}, {@code planId} y los campos computados ({@code planCode}, {@code userEmail},
 * {@code priceMonthly}, {@code priceYearly}) no vuelven. La auditoría se ignora en toEntity.
 */
class CustomerSubscriptionEntityMapperTest {

    private final CustomerSubscriptionEntityMapper mapper = Mappers.getMapper(CustomerSubscriptionEntityMapper.class);

    @Test
    void roundTrip_preservesScalarFields() {
        CustomerSubscription source = CustomerSubscription.builder().id(UUID.randomUUID())
                .status(SubscriptionStatus.ACTIVE).billingPeriod("MONTHLY").stripeCustomerId("cus_1")
                .stripeSubscriptionId("sub_1").currentPeriodStart(Instant.parse("2026-03-01T00:00:00Z"))
                .currentPeriodEnd(Instant.parse("2026-04-01T00:00:00Z")).cancelAt(null).canceledAt(null)
                .trialEndsAt(Instant.parse("2026-03-08T00:00:00Z")).build();

        CustomerSubscriptionEntity entity = mapper.toEntity(source);
        CustomerSubscription result = mapper.toDomain(entity);

        assertThat(result).usingRecursiveComparison().ignoringFields(
                // auditoría resuelta por JPA / ignorada en toEntity
                "createdAt", "updatedAt", "createdBy", "updatedBy",
                // derivados de relaciones gestionadas (user/plan) ignoradas en toEntity
                "userId", "planId", "planCode", "userEmail", "priceMonthly", "priceYearly").isEqualTo(source);
    }

    @Test
    void toEntity_ignoresAuditAndManagedRelations() {
        CustomerSubscription source = CustomerSubscription.builder().id(UUID.randomUUID()).userId(UUID.randomUUID())
                .planId(UUID.randomUUID()).status(SubscriptionStatus.TRIALING).billingPeriod("YEARLY").planCode("PRO")
                .userEmail("sub@nx036.local").createdAt(Instant.now()).updatedAt(Instant.now()).createdBy("creator")
                .updatedBy("editor").build();

        CustomerSubscriptionEntity entity = mapper.toEntity(source);

        assertThat(entity.getCreatedAt()).isNull();
        assertThat(entity.getUpdatedAt()).isNull();
        assertThat(entity.getCreatedBy()).isNull();
        assertThat(entity.getUpdatedBy()).isNull();
        // user/plan las resuelve el repositorio desde userId/planId
        assertThat(entity.getUser()).isNull();
        assertThat(entity.getPlan()).isNull();
    }
}
