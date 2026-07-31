package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.domain.enums.MarginType;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryGroupMemberRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PriceRuleRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductGroupMemberRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MarginServiceTest {

    @Mock
    PriceRuleRepository repository;
    @Mock
    ProductGroupMemberRepository groupMemberRepository;
    @Mock
    CategoryGroupMemberRepository categoryGroupMemberRepository;
    @InjectMocks
    MarginService service;

    private static PriceRuleEntity rule(PriceRuleScope scope, UUID scopeId, MarginType type, String value,
            String min, String max) {
        return PriceRuleEntity.builder().scope(scope).scopeId(scopeId).channel(PriceRuleChannel.STOREFRONT)
                .marginType(type).marginValue(new BigDecimal(value)).active(true)
                .minCostUsd(min == null ? null : new BigDecimal(min))
                .maxCostUsd(max == null ? null : new BigDecimal(max)).build();
    }

    private void rules(PriceRuleEntity... rs) {
        when(repository.findByActiveTrueOrderByPositionAsc()).thenReturn(List.of(rs));
    }

    @Test
    void apply_identityWhenCostNullOrNonPositive() {
        MarginService.PriceWithMargin zero = service.apply(BigDecimal.ZERO, null, null);
        assertThat(zero.retailUsd()).isEqualByComparingTo("0");
        assertThat(zero.appliedRule()).isNull();
        assertThat(service.apply(null, null, null).appliedRule()).isNull();
        // Sin coste válido no se consulta el repositorio de reglas.
        verifyNoInteractions(repository);
    }

    @Test
    void apply_percentageMarkup() {
        rules(rule(PriceRuleScope.GLOBAL, null, MarginType.PERCENTAGE, "150", null, null));
        MarginService.PriceWithMargin r = service.apply(new BigDecimal("10"), null, null);
        // 10 * (1 + 150/100) = 25
        assertThat(r.retailUsd()).isEqualByComparingTo("25.0000");
        assertThat(r.appliedPercentage()).isEqualByComparingTo("150");
        assertThat(r.appliedRule()).isNotNull();
    }

    @Test
    void apply_fixedMarkupDerivesPercentage() {
        rules(rule(PriceRuleScope.GLOBAL, null, MarginType.FIXED, "5", null, null));
        MarginService.PriceWithMargin r = service.apply(new BigDecimal("10"), null, null);
        // 10 + 5 = 15; pct = 5*100/10 = 50
        assertThat(r.retailUsd()).isEqualByComparingTo("15.0000");
        assertThat(r.appliedPercentage()).isEqualByComparingTo("50");
    }

    @Test
    void resolve_picksMoreSpecificProductScopeOverGlobal() {
        UUID productId = UUID.randomUUID();
        rules(rule(PriceRuleScope.GLOBAL, null, MarginType.PERCENTAGE, "100", null, null),
                rule(PriceRuleScope.PRODUCT, productId, MarginType.PERCENTAGE, "200", null, null));
        ProductEntity product = product(productId);

        Optional<PriceRuleEntity> resolved = service.resolve(product, null, new BigDecimal("10"));

        assertThat(resolved).isPresent();
        assertThat(resolved.get().getScope()).isEqualTo(PriceRuleScope.PRODUCT);
    }

    @Test
    void resolve_ignoresRulesFromOtherChannel() {
        PriceRuleEntity integrationOnly = rule(PriceRuleScope.GLOBAL, null, MarginType.PERCENTAGE, "100", null, null);
        integrationOnly.setChannel(PriceRuleChannel.INTEGRATION);
        rules(integrationOnly);
        // Canal por defecto del request = STOREFRONT, así que la regla de INTEGRATION no aplica.
        assertThat(service.resolve(null, null, new BigDecimal("10"))).isEmpty();
    }

    @Test
    void resolve_respectsCostRange() {
        rules(rule(PriceRuleScope.GLOBAL, null, MarginType.PERCENTAGE, "100", "5", "8"));
        assertThat(service.resolve(null, null, new BigDecimal("10"))).isEmpty(); // fuera de rango
        // dentro de rango: re-crea el servicio implícito no hace falta, mismo cache
        assertThat(service.resolve(null, null, new BigDecimal("6"))).isPresent();
    }

    @Test
    void groupRepositoriesAreNotTouchedWithoutGroupRules() {
        lenient().when(repository.findByActiveTrueOrderByPositionAsc())
                .thenReturn(List.of(rule(PriceRuleScope.GLOBAL, null, MarginType.PERCENTAGE, "100", null, null)));
        service.resolve(product(UUID.randomUUID()), null, new BigDecimal("10"));
        verifyNoInteractions(groupMemberRepository, categoryGroupMemberRepository);
    }

    private static ProductEntity product(UUID id) {
        ProductEntity p = ProductEntity.builder().build();
        p.setId(id);
        return p;
    }
}
