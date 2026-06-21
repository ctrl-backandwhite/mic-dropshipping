package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.CustomerSubscriptionUpdateMapper;
import com.nexaplatform.dropshipping.domain.enums.SubscriptionStatus;
import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La actualización parcial de una suscripción copia los campos editables (estado, periodo de facturación,
 * fechas de ciclo, referencias Stripe) y preserva identidad/auditoría.
 */
class CustomerSubscriptionUpdateMapperTest {

    private final CustomerSubscriptionUpdateMapper mapper =
            Mappers.getMapper(CustomerSubscriptionUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesIdentityAndAudit() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");
        Instant periodEnd = Instant.parse("2024-02-01T00:00:00Z");

        CustomerSubscription target = CustomerSubscription.builder().id(id).status(SubscriptionStatus.TRIALING)
                .billingPeriod("MONTH").stripeCustomerId("cus_old").stripeSubscriptionId("sub_old")
                .createdAt(created).createdBy("creator").build();

        CustomerSubscription source = CustomerSubscription.builder().status(SubscriptionStatus.ACTIVE)
                .billingPeriod("YEAR").stripeCustomerId("cus_new").stripeSubscriptionId("sub_new")
                .currentPeriodEnd(periodEnd).build();

        mapper.updateFromModel(source, target);

        // Editables
        assertThat(target.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(target.getBillingPeriod()).isEqualTo("YEAR");
        assertThat(target.getStripeCustomerId()).isEqualTo("cus_new");
        assertThat(target.getStripeSubscriptionId()).isEqualTo("sub_new");
        assertThat(target.getCurrentPeriodEnd()).isEqualTo(periodEnd);

        // Preservados
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getCreatedAt()).isEqualTo(created);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
    }
}
