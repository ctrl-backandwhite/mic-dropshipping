package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.MarginService.PriceWithMargin;
import com.nexaplatform.dropshipping.application.service.PricingChannelHolder;
import com.nexaplatform.dropshipping.application.service.PricingCountryHolder;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.domain.enums.PriceRuleChannel;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
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
    private CustomsValuationService customsValuation;
    private PricingService service;

    @BeforeEach
    void setup() {
        currencyService = mock(CurrencyRateService.class);
        marginService = mock(MarginService.class);
        promotionService = sinPromociones();
        customsValuation = mock(CustomsValuationService.class);
        // Por defecto, un destino que NO cobra derecho por artículo: es el caso de la mayoría del mundo.
        lenient().when(customsValuation.perArticleFeeUsdCents(any())).thenReturn(0);
        service = new PricingService(currencyService, promotionService, marginService, customsValuation);
        CurrencyHolder.clear();
        PricingChannelHolder.set(PriceRuleChannel.STOREFRONT);
        PricingCountryHolder.clear();
    }

    @AfterEach
    void cleanup() {
        CurrencyHolder.clear();
        PricingChannelHolder.set(PriceRuleChannel.STOREFRONT);
        PricingCountryHolder.clear();
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

        PricedAmount priced = service
                .priceFor(ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY").build());

        assertThat(priced.discountPercent()).isNull();
        assertThat(priced.originalFormatted()).isNull();
        org.mockito.Mockito.verify(promotionService, org.mockito.Mockito.never()).applyAutomatic(any(), any(), any());
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
     * El margen se aplica sobre el precio COMPLETO del proveedor: base + IVA chino + porte, no solo
     * sobre la base.
     *
     * <p>Qué se rompería en producción si esta prueba fallara: el catálogo entero se vendería un 20-25 %
     * por debajo de lo acordado. Con el margen solo sobre la base, un artículo de 100 CNY con 13 de IVA
     * y 16 de porte salía a 25 + 1,30 + 1,60 = 27,90 USD; sobre el total sale a 32,25. Esos 4,35 USD por
     * unidad son la diferencia entre ganar dinero con el porte y regalarlo.
     */
    @Test
    void elMargenSeAplicaSobreLaBaseElIvaYElPorte() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("10")));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("10.00"), new BigDecimal("25.00"), null, new BigDecimal("150")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY")
                .margenInternoPct(new BigDecimal("13")).shippingCny(new BigDecimal("16.00")).build();

        PricedAmount priced = service.priceFor(p);

        // 10 USD de base × 2,5 = 25; el IVA (1,30) y el porte (1,60) llevan el MISMO factor.
        assertThat(priced.baseRetailUsd()).isEqualByComparingTo("25.00");
        assertThat(priced.margenInternoUsd()).isEqualByComparingTo("3.25");
        assertThat(priced.shippingUsd()).isEqualByComparingTo("4.00");
        assertThat(priced.retailUsd()).isEqualByComparingTo("32.25");
    }

    /**
     * El porte del PROVEEDOR se conserva aparte del que se cobra al cliente.
     *
     * <p>Qué se rompería en producción si esta prueba fallara: la subvención por porte repetido devolvería
     * el porte con margen en vez de los 16 CNY que de verdad se pagan al proveedor, regalando el margen
     * dos veces —una en la bolsa y otra en la regla de la ganancia sobrante—.
     */
    @Test
    void elPorteDelProveedorSeGuardaSinMargenJuntoAlCobrado() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("10")));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("10.00"), new BigDecimal("25.00"), null, new BigDecimal("150")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY")
                .margenInternoPct(new BigDecimal("13")).shippingCny(new BigDecimal("16.00")).build();

        PricedAmount priced = service.priceFor(p);

        assertThat(priced.supplierShippingUsd()).isEqualByComparingTo("1.60");
        // Ganancia = 32,25 cobrados − (10 de coste + 1,30 de IVA + 1,60 de porte) que se le deben al proveedor.
    }

    /**
     * Sin coste no hay factor que aplicar: el IVA y el porte se quedan como vienen en vez de reventar con
     * una división por cero.
     */
    @Test
    void sinCosteNoHayMargenInternoYElPorteNoLlevaMargen() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("10")));
        when(marginService.apply(any(), any(), any()))
                .thenReturn(new PriceWithMargin(BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(BigDecimal.ZERO).currency("CNY")
                .margenInternoPct(new BigDecimal("13")).shippingCny(new BigDecimal("16.00")).build();

        PricedAmount priced = service.priceFor(p);

        // El margen interno es un PORCENTAJE sobre el coste, así que con el coste a cero no hay nada
        // que ganar: cero. Antes daba 1,30 porque viajaba como importe fijo y se cobraba aunque el
        // producto no costara nada — justo el disparate que el porcentaje evita.
        assertThat(priced.margenInternoUsd()).isEqualByComparingTo("0");
        // El porte sí sigue ahí, y sin margen encima: eso no cambia.
        assertThat(priced.shippingUsd()).isEqualByComparingTo("1.60");
    }

    /**
     * El recargo fijo por producto (surcharge_cny, 30-ago-2026) se suma al total SIN margen: es un cargo
     * directo que fija el admin, no un coste de proveedor. Default 0 = sin efecto.
     *
     * <p>Qué se rompería en producción si esta prueba fallara: el admin fija un recargo de 2 CNY y el
     * cliente lo pagaría multiplicado por el margen (o no se cobraría), desviándose de lo que el panel
     * anunció.
     */
    @Test
    void elRecargoSeSumaAlTotalSinMargen() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("10")));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("10.00"), new BigDecimal("25.00"), null, new BigDecimal("150")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");
        when(currencyService.formatDisplay(any(BigDecimal.class), anyString())).thenReturn("2,00 $");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY")
                .surchargeCny(new BigDecimal("20.00")).build();

        PricedAmount priced = service.priceFor(p);

        // base 25,00 + recargo 2,00 (20 CNY / 10) = 27,00. El recargo NO se multiplica por el margen.
        assertThat(priced.surchargeUsd()).isEqualByComparingTo("2.00");
        assertThat(priced.baseRetailUsd()).isEqualByComparingTo("25.00");
        assertThat(priced.retailUsd()).isEqualByComparingTo("27.00");
        assertThat(priced.surchargeFormatted()).isNotEmpty();
    }

    /** Recargo a 0 (default) no altera el total ni aparece en el desglose. */
    @Test
    void recargoCeroNoAlteraElPrecio() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("10")));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("10.00"), new BigDecimal("25.00"), null, new BigDecimal("150")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY")
                .surchargeCny(BigDecimal.ZERO).build();

        PricedAmount priced = service.priceFor(p);

        assertThat(priced.surchargeUsd()).isEqualByComparingTo("0");
        assertThat(priced.retailUsd()).isEqualByComparingTo("25.00");
    }

    /** Recargo null (producto sin valor) se trata como 0: no reventar la cadena de sumas. */
    @Test
    void recargoNullSeTrataComoCero() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("10")));
        when(marginService.apply(any(), any(), any())).thenReturn(
                new PriceWithMargin(new BigDecimal("10.00"), new BigDecimal("25.00"), null, new BigDecimal("150")));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        when(currencyService.symbolOf(anyString())).thenReturn("$");

        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("100.00")).currency("CNY").surchargeCny(null)
                .build();

        PricedAmount priced = service.priceFor(p);

        assertThat(priced.surchargeUsd()).isEqualByComparingTo("0");
        assertThat(priced.retailUsd()).isEqualByComparingTo("25.00");
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

    /**
     * El desglose que se enseña tiene que SUMAR el total que se cobra.
     *
     * <p>Son seis importes convertidos y redondeados por separado, y ese redondeo deja un residuo de
     * céntimos que tiene que ir a alguna parte: si no, el administrador ve seis líneas que no dan la
     * cifra de abajo y deja de fiarse de las seis. El residuo lo absorbe la BASE, la partida mayor.
     *
     * <p>Los importes están elegidos para que la conversión no dé cifras redondas —es justo cuando
     * aparece el descuadre—: con una tasa de 0,92 y seis componentes, componer en euros y componer en
     * dólares difieren en un céntimo.
     */
    @Test
    void elDesgloseSumaExactamenteElTotalQueSeCobra() {
        when(currencyService.toUsd(any(BigDecimal.class), eq("CNY")))
                .thenAnswer(inv -> ((BigDecimal) inv.getArgument(0)).divide(new BigDecimal("7.24"), 6,
                        java.math.RoundingMode.HALF_UP));
        when(marginService.apply(any(), any(), any())).thenReturn(new PriceWithMargin(new BigDecimal("3.867403"),
                new BigDecimal("9.668508"), null, new BigDecimal("150")));
        // Tasa que NO deja cifras redondas: es donde el residuo de redondeo se nota.
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0) == null
                ? null
                : ((BigDecimal) inv.getArgument(0)).multiply(new BigDecimal("0.92")));
        when(currencyService.symbolOf(anyString())).thenReturn("€");
        when(currencyService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) == null
                        ? null
                        : ((BigDecimal) inv.getArgument(0)).setScale(2, java.math.RoundingMode.HALF_UP)
                                .toPlainString());

        // Destino que SÍ cobra derecho por artículo (la UE): si no, la línea de arancel valdría cero y
        // esta prueba dejaría de cubrir justo la sexta línea que dice comprobar.
        PricingCountryHolder.set("ES");
        when(customsValuation.perArticleFeeUsdCents("ES")).thenReturn(325);
        ProductEntity p = ProductEntity.builder().basePrice(new BigDecimal("28.00")).currency("CNY")
                .margenInternoPct(new BigDecimal("13")).shippingCny(new BigDecimal("16.00"))
                .surchargeCny(new BigDecimal("2.50")).shippingUserCny(new BigDecimal("16.00"))
                .dutyUserCny(new BigDecimal("8.00")).build();

        PricedAmount priced = service.priceFor(p);

        BigDecimal suma = new BigDecimal(priced.baseFormatted()).add(new BigDecimal(priced.margenInternoFormatted()))
                .add(new BigDecimal(priced.shippingFormatted())).add(new BigDecimal(priced.surchargeFormatted()))
                .add(new BigDecimal(priced.shippingUserFormatted())).add(new BigDecimal(priced.dutyUserFormatted()));

        assertThat(suma).as("las seis líneas del desglose tienen que dar el total que se cobra")
                .isEqualByComparingTo(new BigDecimal(priced.displayFormatted()));
    }

    /**
     * El subsidio de arancel NO es un descuento: es un importe que el comprador paga por adelantado y que
     * después se le descuenta del derecho de aduana de su pedido. Donde no hay derecho que descontar
     * —Latinoamérica, Estados Unidos y los otros cincuenta y nueve países con el importe por artículo a
     * cero— cobrarlo es cobrar de más por algo que ese comprador no llega a pagar nunca.
     */
    @Test
    void elSubsidioDeArancelNoSeCobraDondeElDestinoNoCobraDerechoPorArticulo() {
        tarifaSimple();
        PricingCountryHolder.set("MX");
        when(customsValuation.perArticleFeeUsdCents("MX")).thenReturn(0);

        PricedAmount priced = service.priceFor(productoConSubsidioDeArancel());

        assertThat(new BigDecimal(priced.dutyUserFormatted()))
                .as("en un destino sin derecho por artículo el subsidio de arancel va a cero")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Y en la Unión Europea, donde sí se paga el derecho por línea, se cobra el importe del campo. */
    @Test
    void elSubsidioDeArancelSeCobraEnteroDondeElDestinoSiCobraDerechoPorArticulo() {
        tarifaSimple();
        PricingCountryHolder.set("ES");
        when(customsValuation.perArticleFeeUsdCents("ES")).thenReturn(325);

        PricedAmount priced = service.priceFor(productoConSubsidioDeArancel());

        assertThat(new BigDecimal(priced.dutyUserFormatted()))
                .as("en la UE se cobra el importe del campo, sin recortar").isGreaterThan(BigDecimal.ZERO);
    }

    /** Sin país resuelto no se cobra: un dato que falta no puede encarecer a nadie. */
    @Test
    void sinPaisConocidoElSubsidioDeArancelTampocoSeCobra() {
        tarifaSimple();
        PricingCountryHolder.clear();
        when(customsValuation.perArticleFeeUsdCents(null)).thenReturn(0);

        PricedAmount priced = service.priceFor(productoConSubsidioDeArancel());

        assertThat(new BigDecimal(priced.dutyUserFormatted())).isEqualByComparingTo(BigDecimal.ZERO);
    }

    /** Un producto con la bolsa de arancel puesta; lo demás al mínimo para no enturbiar la comprobación. */
    private static ProductEntity productoConSubsidioDeArancel() {
        return ProductEntity.builder().basePrice(new BigDecimal("28.00")).currency("CNY")
                .dutyUserCny(new BigDecimal("8.00")).build();
    }

    /** Conversión y formato sin sorpresas: 1 CNY = 1 USD = 1 de display. */
    private void tarifaSimple() {
        when(currencyService.toUsd(any(BigDecimal.class), anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(marginService.apply(any(), any(), any()))
                .thenAnswer(inv -> new PriceWithMargin(inv.getArgument(0), inv.getArgument(0), null, null));
        when(currencyService.usdToDisplay(any(BigDecimal.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(currencyService.symbolOf(anyString())).thenReturn("$");
        when(currencyService.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(inv -> inv.getArgument(0) == null
                        ? null
                        : ((BigDecimal) inv.getArgument(0)).setScale(2, java.math.RoundingMode.HALF_UP)
                                .toPlainString());
    }
}
