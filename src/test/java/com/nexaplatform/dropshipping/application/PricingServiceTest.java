package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.MarginService.PriceWithMargin;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;

import java.math.BigDecimal;

import static org.mockito.Mockito.lenient;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DROP-449 — tests unitarios del adapter 1688 → moneda local.
 *
 * Cubre la cadena: supplierAmount (CNY) → cost USD (via CurrencyRateService)
 * → retail USD (via MarginService) → displayAmount (locale del usuario).
 */
class PricingServiceTest {

    private CurrencyRateService currencyService;
    private MarginService marginService;
    private PromotionService promotionService;
    private PricingService service;

    @BeforeEach
    void setup() {
        currencyService = mock(CurrencyRateService.class);
        marginService = mock(MarginService.class);
        promotionService = sinPromociones();
        service = new PricingService(currencyService, promotionService, marginService);
        CurrencyHolder.clear();
        PricingChannelHolder.set(PriceRuleChannel.STOREFRONT);
    }

    @AfterEach
    void cleanup() {
        CurrencyHolder.clear();
        PricingChannelHolder.set(PriceRuleChannel.STOREFRONT);
    }

    /**
     * REGLA ESTRICTA: el canal de integración (Shopify/WooCommerce/API) NO recibe rebajas. Se comprueba
     * por el contrato —no se consulta ni una sola vez a la promoción— para que la regla no dependa de
     * que el mock «casualmente» no descuente.
     */
    @Test
    void priceFor_integrationChannel_neverAppliesPromotions() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("14.00"));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("14.00"), new BigDecimal("21.00"), null, new BigDecimal("50.0")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");
        PricingChannelHolder.set(PriceRuleChannel.INTEGRATION);

        PricedAmount priced = service.priceFor(
                ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY").build());

        assertThat(priced.discountPercent()).isNull();
        assertThat(priced.originalFormatted()).isNull();
        org.mockito.Mockito.verify(promotionService, org.mockito.Mockito.never())
                .applyAutomatic(any(), any(), any());
    }

    /** En el escaparate sí se pregunta a la promoción (aunque este mock no descuente). */
    @Test
    void priceFor_storefrontChannel_consultsPromotions() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("14.00"));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("14.00"), new BigDecimal("21.00"), null, new BigDecimal("50.0")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");
        PricingChannelHolder.set(PriceRuleChannel.STOREFRONT);

        service.priceFor(ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY").build());

        org.mockito.Mockito.verify(promotionService).applyAutomatic(any(), any(), any());
    }

    @Test
    void priceFor_convertsCnyToUsdAndAppliesMargin_thenDisplaysInUsd() {
        // 100 CNY → 14 USD (rate 7.14 CNY/USD)
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("14.00"));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("14.00"), new BigDecimal("21.00"), null, new BigDecimal("50.0")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY").build();

        PricedAmount priced = service.priceFor(p);

        assertThat(priced.costUsd()).isEqualByComparingTo("14.00");
        assertThat(priced.retailUsd()).isEqualByComparingTo("21.00");
        assertThat(priced.displayAmount()).isEqualByComparingTo("21.00");
        assertThat(priced.displaySymbol()).isEqualTo("$");
        assertThat(priced.appliedMarginPercent()).isEqualByComparingTo("50.0");
    }

    @Test
    void priceFor_variantPriceOverridesProductBase() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("7.50"));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("7.50"), new BigDecimal("12.00"), null, new BigDecimal("60.0")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY").build();
        ProductVariantEntity v = ProductVariantEntity.builder().price(new BigDecimal("50.00")).build();

        PricedAmount priced = service.priceFor(p, v);

        // El cost debería haberse calculado sobre el precio de la variante (50 CNY → 7.50 USD)
        assertThat(priced.costUsd()).isEqualByComparingTo("7.50");
        assertThat(priced.retailUsd()).isEqualByComparingTo("12.00");
    }

    @Test
    void priceFor_handlesNullBasePrice_returnsNullCost() {
        when(marginService.apply(any(), any(), any())).thenReturn(new PriceWithMargin(null, null, null, null));
        when(currencyService.usdToDisplay(any())).thenReturn(null);
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(null).currency("CNY").build();

        PricedAmount priced = service.priceFor(p);

        assertThat(priced.costUsd()).isNull();
        assertThat(priced.retailUsd()).isNull();
    }

    @Test
    void priceFor_displayCurrencyFollowsCurrencyHolder() {
        CurrencyHolder.set("EUR");
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("10.00"));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("10.00"), new BigDecimal("15.00"), null, new BigDecimal("50.0")));
        when(currencyService.usdToDisplay(new BigDecimal("15.00"))).thenReturn(new BigDecimal("13.80"));
        when(currencyService.symbolOf("EUR")).thenReturn("€");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("71.40")).currency("CNY").build();

        PricedAmount priced = service.priceFor(p);

        assertThat(priced.displayCurrency()).isEqualTo("EUR");
        assertThat(priced.displaySymbol()).isEqualTo("€");
        assertThat(priced.displayAmount()).isEqualByComparingTo("13.80");
    }

    @Test
    void priceFor_zeroSupplierPrice_returnsZeroAcrossPipeline() {
        // DROP-449 caso borde: precio 0 (regalo / promo). No debería romper la pipeline
        // ni amplificarse por margen — 0 × cualquier margen = 0.
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(BigDecimal.ZERO);
        when(marginService.apply(any(), any(), any()))
                .thenReturn(new PriceWithMargin(BigDecimal.ZERO, BigDecimal.ZERO, null, new BigDecimal("50.0")));
        when(currencyService.usdToDisplay(any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(BigDecimal.ZERO).currency("CNY").build();

        PricedAmount priced = service.priceFor(p);
        assertThat(priced.costUsd()).isEqualByComparingTo("0");
        assertThat(priced.retailUsd()).isEqualByComparingTo("0");
        assertThat(priced.displayAmount()).isEqualByComparingTo("0");
    }

    @Test
    void priceFor_convertsToEur_appliesExchangeRate() {
        // DROP-449: conversión USD→EUR específica con tasa documentada.
        CurrencyHolder.set("EUR");
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("100.00"));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("100.00"), new BigDecimal("150.00"), null, new BigDecimal("50.0")));
        // 150 USD * 0.92 = 138 EUR (tasa de cambio fixed)
        when(currencyService.usdToDisplay(new BigDecimal("150.00"))).thenReturn(new BigDecimal("138.00"));
        when(currencyService.symbolOf("EUR")).thenReturn("€");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("700.00")).currency("CNY").build();

        PricedAmount priced = service.priceFor(p);
        assertThat(priced.displayAmount()).isEqualByComparingTo("138.00");
        assertThat(priced.displayCurrency()).isEqualTo("EUR");
    }

    @Test
    void priceFor_staleExchangeRate_stillProducesValidPrice() {
        // DROP-449: si el currency service devuelve una tasa "rancia" (lastUpdated muy viejo),
        // la pipeline debe seguir funcionando — el rate service se encarga del fallback;
        // PricingService no debería fallar por eso. Lo emulamos devolviendo una tasa razonable.
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("13.50")); // valor "rancio" pero válido
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("13.50"), new BigDecimal("20.25"), null, new BigDecimal("50.0")));
        when(currencyService.usdToDisplay(any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY").build();

        PricedAmount priced = service.priceFor(p);
        assertThat(priced.costUsd()).isNotNull();
        assertThat(priced.retailUsd()).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void priceFor_tieredPricing_minMaxBoundaries() {
        // DROP-449: precios por tramos — el adapter no calcula tramos él mismo
        // (eso lo hace MarginService), pero verificamos que el cost USD respete
        // el precio base sin tramos cuando no hay variante.
        when(currencyService.toUsd(new BigDecimal("50.00"), "CNY")).thenReturn(new BigDecimal("7.00")); // min
        when(currencyService.toUsd(new BigDecimal("1000.00"), "CNY")).thenReturn(new BigDecimal("140.00")); // max
        when(marginService.apply(any(), any(), any())).thenAnswer(inv -> {
            BigDecimal cost = inv.getArgument(0);
            return new PriceWithMargin(cost, cost.multiply(new BigDecimal("1.5")), null, new BigDecimal("50.0"));
        });
        when(currencyService.usdToDisplay(any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        PricedAmount min = service
                .priceFor(ProductEntity.builder().basePrice(new BigDecimal("50.00")).currency("CNY").build());
        PricedAmount max = service
                .priceFor(ProductEntity.builder().basePrice(new BigDecimal("1000.00")).currency("CNY").build());

        assertThat(min.costUsd()).isEqualByComparingTo("7.00");
        assertThat(max.costUsd()).isEqualByComparingTo("140.00");
        assertThat(max.retailUsd()).isGreaterThan(min.retailUsd());
    }

    @Test
    void priceFor_defaultsCurrencyToCnyWhenMissing() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY"))).thenReturn(new BigDecimal("5.00"));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("5.00"), new BigDecimal("8.00"), null, new BigDecimal("60.0")));
        when(currencyService.usdToDisplay(any())).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("35.00")).currency(null) // explicit null
                .build();

        PricedAmount priced = service.priceFor(p);

        // Should still convert as if it were CNY (default).
        assertThat(priced.costUsd()).isEqualByComparingTo("5.00");
    }

    /**
     * Motor de promociones que no rebaja nada: estas pruebas miden el pipeline de precio (coste →
     * margen → divisa), no las rebajas, y una promoción activa cambiaría todos los importes esperados.
     */
    private static PromotionService sinPromociones() {
        PromotionService p = mock(PromotionService.class);
        lenient().when(p.applyAutomatic(any(), any(), any())).thenAnswer(inv -> {
            java.math.BigDecimal precio = inv.getArgument(1);
            return new PromotionService.Discounted(precio, precio, java.math.BigDecimal.ZERO, null, null);
        });
        return p;
    }
}
