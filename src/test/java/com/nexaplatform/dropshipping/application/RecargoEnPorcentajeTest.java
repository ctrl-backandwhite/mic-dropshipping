package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El recargo, en porcentaje sobre el coste del proveedor.
 *
 * <p>Regla del titular (25-sep-2026): «el recargo por tramos debe aplicar por encima de cualquier
 * regla de precios, y si no hay nada aplica el global; y debe ser en porcentajes también, como el
 * margen interno».
 *
 * <p><b>Por qué se cambió.</b> Guardado como importe fijo en yuanes, el recargo no seguía al coste:
 * si el proveedor subía el precio, el recargo se quedaba donde estaba y había que rehacerlo a mano
 * producto a producto, o se desfasaba en silencio. Es el mismo defecto que tenía el margen interno
 * antes de la v174, y se arregla igual.
 *
 * <p>Medido sobre preproducción antes de trasladarlo: los 257 productos con recargo iban del 59 % al
 * 250 % de la base, así que —al revés que el margen interno, que valía 50 % clavado— el traslado tuvo
 * que ser producto a producto. Un porcentaje único habría movido el precio de casi todos.
 */
class RecargoEnPorcentajeTest {

    private PricingService pricingService;

    @BeforeEach
    void setup() {
        CurrencyRateService currencyService = mock(CurrencyRateService.class);
        when(currencyService.toUsd(Mockito.any(), Mockito.anyString())).thenAnswer(i -> i.getArgument(0));
        when(currencyService.usdToDisplay(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(currencyService.symbolOf(Mockito.anyString())).thenReturn("$");
        lenient().when(currencyService.localeOf(Mockito.anyString())).thenReturn("en-US");

        MarginService marginService = mock(MarginService.class);
        // Sin margen comercial encima: así lo que se mide es EXACTAMENTE lo que aporta el recargo, sin
        // que el factor de la regla de precio lo enmascare.
        when(marginService.apply(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(
                i -> new MarginService.PriceWithMargin(i.getArgument(0), i.getArgument(0), null, BigDecimal.ZERO));

        CustomsValuationService aduanas = mock(CustomsValuationService.class);
        lenient().when(aduanas.perArticleFeeUsdCents(any())).thenReturn(0);

        PromotionService sinPromos = mock(PromotionService.class);
        lenient().when(sinPromos.applyAutomatic(any(), any(), any())).thenAnswer(i -> {
            BigDecimal precio = i.getArgument(1);
            return new PromotionService.Discounted(precio, precio, BigDecimal.ZERO, null, null);
        });

        pricingService = new PricingService(currencyService, sinPromos, marginService, aduanas);
    }

    /**
     * LO QUE MOTIVÓ EL CAMBIO: el recargo sigue al coste solo.
     *
     * <p>Con el importe fijo, un producto que pasa de costar 10 a costar 20 seguía llevando el mismo
     * recargo: el cargo que el dueño había calibrado sobre un coste se quedaba a la mitad en
     * proporción, y nada avisaba. Esta prueba es la que se pondría roja si alguien volviera a guardar
     * un importe.
     */
    @Test
    @DisplayName("el recargo sigue al coste del proveedor sin que nadie lo toque")
    void elRecargoSigueAlCoste() {
        ProductEntity barato = productoCon(new BigDecimal("10.00"), new BigDecimal("30.00"));
        ProductEntity caro = productoCon(new BigDecimal("20.00"), new BigDecimal("30.00"));

        assertThat(pricingService.priceFor(barato).surchargeUsd()).isEqualByComparingTo("3.00");
        assertThat(pricingService.priceFor(caro).surchargeUsd()).isEqualByComparingTo("6.00");
    }

    /**
     * EL CONTROL del sitio donde se suma: el recargo NO lleva margen encima.
     *
     * <p>Es un cargo directo que decide quien administra, no un coste de proveedor sobre el que se
     * gane. Si se multiplicara por el factor de margen —como sí hacen la base y el envío— el dueño
     * escribiría un 30 % y el cliente pagaría bastante más, sin que el desglose lo explicara.
     */
    @Test
    @DisplayName("el recargo se suma sin margen encima")
    void elRecargoNoLlevaMargen() {
        ProductEntity p = productoCon(new BigDecimal("10.00"), new BigDecimal("30.00"));

        PricingService.PricedAmount precio = pricingService.priceFor(p);

        // 10,00 de base + 3,00 de recargo = 13,00 exactos.
        assertThat(precio.surchargeUsd()).isEqualByComparingTo("3.00");
        assertThat(precio.displayAmount()).isEqualByComparingTo("13.00");
    }

    /**
     * EL RECARGO DEL TRAMO MANDA, que es lo que pidió el titular: por encima de cualquier otra regla.
     *
     * <p>El tramo de cien unidades declara el suyo y se aplica ese, aunque el producto tenga otro.
     */
    @Test
    @DisplayName("el recargo del tramo manda sobre el global del producto")
    void elRecargoDelTramoManda() {
        ProductEntity p = productoCon(new BigDecimal("10.00"), new BigDecimal("30.00"));
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00"), null),
                tramo(p, 100, new BigDecimal("10.00"), new BigDecimal("5.00")));

        PricingService.PricedAmount cien = pricingService.priceFor(p, p.getVariants().get(0), 100, escalera);

        // El 5 % del tramo sobre un coste de 10,00 son 0,50; con el 30 % del producto serían 3,00.
        assertThat(cien.surchargeUsd()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("sin recargo propio, el tramo hereda el global")
    void sinRecargoPropioElTramoHereda() {
        // EL control de la precedencia: «nulo no es cero». Si el tramo sin recargo propio se tomara
        // como un cero, los 3.240 tramos que hoy heredan dejarían de cobrar el recargo del producto.
        ProductEntity p = productoCon(new BigDecimal("10.00"), new BigDecimal("30.00"));
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00"), null),
                tramo(p, 100, new BigDecimal("10.00"), null));

        PricingService.PricedAmount cien = pricingService.priceFor(p, p.getVariants().get(0), 100, escalera);

        assertThat(cien.surchargeUsd()).isEqualByComparingTo("3.00");
    }

    @Test
    @DisplayName("un cero en el tramo anula el recargo del producto")
    void unCeroEnElTramoAnulaElGlobal() {
        // Cero y nulo dicen cosas distintas, y aquí se ve la diferencia: cero es una decisión —«este
        // tramo no lleva cargo»— y tiene que poder escribirse aunque el producto sí lo tenga.
        ProductEntity p = productoCon(new BigDecimal("10.00"), new BigDecimal("30.00"));
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00"), null),
                tramo(p, 100, new BigDecimal("10.00"), BigDecimal.ZERO));

        PricingService.PricedAmount cien = pricingService.priceFor(p, p.getVariants().get(0), 100, escalera);

        assertThat(cien.surchargeUsd()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("un producto sin porcentaje no paga recargo, y no se le inventa uno")
    void sinPorcentajeNoHayRecargo() {
        // Nulo aporta CERO. Lo contrario —un porcentaje por defecto— encarecería en silencio a
        // cualquier producto al que le falte el dato, que es justo lo que no puede hacer un cambio
        // que solo pretende cambiar la unidad en la que se guarda un número.
        ProductEntity p = productoCon(new BigDecimal("10.00"), null);

        PricingService.PricedAmount precio = pricingService.priceFor(p);

        assertThat(precio.surchargeUsd()).isEqualByComparingTo("0");
        assertThat(precio.displayAmount()).isEqualByComparingTo("10.00");
    }

    /**
     * En la tabla de cantidades el recargo BAJA con el tramo, igual que el coste.
     *
     * <p>Es la consecuencia de calcularlo sobre el importe que se está tarificando, y es lo que se
     * espera de un porcentaje: quien compra al por mayor no paga el recargo del precio unitario.
     */
    @Test
    @DisplayName("el recargo baja con el tramo en la misma proporción que el coste")
    void elRecargoBajaConElTramo() {
        ProductEntity p = productoCon(new BigDecimal("10.00"), new BigDecimal("30.00"));
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00"), null),
                tramo(p, 100, new BigDecimal("8.00"), null));

        PricingService.PricedAmount una = pricingService.priceFor(p, p.getVariants().get(0), 1, escalera);
        PricingService.PricedAmount cien = pricingService.priceFor(p, p.getVariants().get(0), 100, escalera);

        // El coste baja de 10,00 a 8,00 —el 80 %— y el recargo de 3,00 a 2,40, el mismo 80 %.
        assertThat(una.surchargeUsd()).isEqualByComparingTo("3.00");
        assertThat(cien.surchargeUsd()).isEqualByComparingTo("2.40");
    }

    private static ProductEntity productoCon(BigDecimal coste, BigDecimal recargoPct) {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("X").basePrice(coste).currency("CNY")
                .surchargePct(recargoPct).build();
        ProductVariantEntity v = ProductVariantEntity.builder().product(p).sku("SKU-1").price(coste).stock(5)
                .options(Map.of()).active(true).build();
        p.setVariants(List.of(v));
        return p;
    }

    private static ProductPriceTierEntity tramo(ProductEntity p, int minQty, BigDecimal unitPrice,
            BigDecimal recargoPct) {
        return ProductPriceTierEntity.builder().product(p).minQty(minQty).unitPrice(unitPrice).surchargePct(recargoPct)
                .build();
    }
}
