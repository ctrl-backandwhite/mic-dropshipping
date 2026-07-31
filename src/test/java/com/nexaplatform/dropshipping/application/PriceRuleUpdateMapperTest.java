package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.mapper.PriceRuleUpdateMapper;
import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import com.nexaplatform.dropshipping.domain.model.PriceRule;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * La actualización parcial de una regla de margen copia los campos editables (scope, marginType,
 * marginValue, límites de coste, active, position, description) y preserva identidad/canal/auditoría
 * (el canal no se gestiona desde el admin).
 */
class PriceRuleUpdateMapperTest {

    private final PriceRuleUpdateMapper mapper = Mappers.getMapper(PriceRuleUpdateMapper.class);

    @Test
    void updateFromModel_copiesEditableFieldsAndPreservesIdentityChannelAndAudit() {
        UUID id = UUID.randomUUID();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        PriceRule target = PriceRule.builder().id(id).scope(PriceRuleScope.GLOBAL).marginType(MarginType.PERCENTAGE)
                .marginValue(new BigDecimal("150")).active(false).position(1).description("old")
                .channel(PriceRuleChannel.STOREFRONT).createdAt(created).createdBy("creator").build();

        PriceRule source = PriceRule.builder().scope(PriceRuleScope.CATEGORY).marginType(MarginType.FIXED)
                .marginValue(new BigDecimal("75")).minCostUsd(new BigDecimal("5")).maxCostUsd(new BigDecimal("500"))
                .active(true).position(9).description("new").channel(PriceRuleChannel.INTEGRATION).build();

        mapper.updateFromModel(source, target);

        // Editables
        assertThat(target.getScope()).isEqualTo(PriceRuleScope.CATEGORY);
        assertThat(target.getMarginType()).isEqualTo(MarginType.FIXED);
        assertThat(target.getMarginValue()).isEqualByComparingTo("75");
        assertThat(target.getMinCostUsd()).isEqualByComparingTo("5");
        assertThat(target.getMaxCostUsd()).isEqualByComparingTo("500");
        assertThat(target.isActive()).isTrue();
        assertThat(target.getPosition()).isEqualTo(9);
        assertThat(target.getDescription()).isEqualTo("new");

        // Preservados
        assertThat(target.getId()).isEqualTo(id);
        assertThat(target.getChannel()).isEqualTo(PriceRuleChannel.STOREFRONT);
        assertThat(target.getCreatedAt()).isEqualTo(created);
        assertThat(target.getCreatedBy()).isEqualTo("creator");
    }
}
