package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.SubscriptionPlanUpdateMapper;
import com.nexaplatform.dropshipping.domain.model.SubscriptionPlan;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El mapper de actualización parcial de plan copia los campos editables (code, name, precios,
 * active, posición...) y preserva identidad y auditoría.
 */
class SubscriptionPlanUpdateMapperTest {

    private final SubscriptionPlanUpdateMapper mapper = Mappers.getMapper(SubscriptionPlanUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesIdentityAndAudit() {
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        SubscriptionPlan target = SubscriptionPlan.builder()
                .id(id).code("OLD").name("Old Plan").description("old")
                .priceMonthlyCents(100).priceYearlyCents(1000).currency("USD")
                .active(false).position(1)
                .createdAt(createdAt).createdBy("creator").updatedBy("editor1")
                .build();

        SubscriptionPlan source = SubscriptionPlan.builder()
                .id(UUID.randomUUID()).code("NEW").name("New Plan").description("new")
                .priceMonthlyCents(2000).priceYearlyCents(20000).currency("EUR")
                .active(true).position(5)
                .createdAt(Instant.parse("2099-01-01T00:00:00Z")).createdBy("attacker").updatedBy("editor2")
                .build();

        mapper.updateFromModel(source, target);

        // Editables: se actualizan
        assertThat(target.getCode()).isEqualTo("NEW");
        assertThat(target.getName()).isEqualTo("New Plan");
        assertThat(target.getDescription()).isEqualTo("new");
        assertThat(target.getPriceMonthlyCents()).isEqualTo(2000);
        assertThat(target.getPriceYearlyCents()).isEqualTo(20000);
        assertThat(target.getCurrency()).isEqualTo("EUR");
        assertThat(target.isActive()).isTrue();
        assertThat(target.getPosition()).isEqualTo(5);

        // Identidad / auditoría: se preservan
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getCreatedAt()).isEqualTo(createdAt);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
        assertThat(target.getUpdatedBy()).isEqualTo("editor1");
    }
}
