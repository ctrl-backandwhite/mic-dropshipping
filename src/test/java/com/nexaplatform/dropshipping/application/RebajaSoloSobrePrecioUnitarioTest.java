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
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Las rebajas son del PRECIO UNITARIO. Sobre un precio de mayoreo no se descuenta nada.
 *
 * <p>Regla del titular del 25-sep-2026, y cubre TODA forma de descuento del escaparate: promociones
 * automáticas, rebajas y campañas. Los cupones se cortan por la misma regla en el checkout y en el
 * pedido, que es donde se aplican.
 *
 * <p><b>Qué se rompería sin esto.</b> El escalón por cantidad YA es el descuento que concede el
 * proveedor por volumen. Encadenarle encima una campaña del escaparate descuenta dos veces sobre el
 * margen más estrecho del catálogo: un -30 % sobre el tramo de 1.000 unidades no es una campaña, es
 * vender por debajo de coste mil veces en un mismo pedido. Y el fallo no se ve venir, porque el
 * precio unitario de ese mismo producto sí puede rebajarse sin problema.
 */
class RebajaSoloSobrePrecioUnitarioTest {

    /**
     * Una campaña viva del 10 %, para que se note si se aplica donde no debe.
     *
     * <p>Un 10 % y no un 50 %: la rebaja tiene como SUELO el precio base del producto (coste × margen),
     * y con un producto cuyo precio es casi todo base no queda recorrido para descontar. Con el suelo
     * tocando, la prueba de control saldría verde sin que la rebaja se hubiera aplicado nunca —que es
     * como se escribió la primera vez—.
     */
    private static final BigDecimal RESTO_TRAS_LA_REBAJA = new BigDecimal("0.90");

    private PricingService pricingService;

    @BeforeEach
    void setup() {
        CurrencyRateService currencyService = mock(CurrencyRateService.class);
        when(currencyService.toUsd(Mockito.any(), Mockito.anyString())).thenAnswer(i -> i.getArgument(0));
        when(currencyService.usdToDisplay(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(currencyService.symbolOf(Mockito.anyString())).thenReturn("$");
        lenient().when(currencyService.localeOf(Mockito.anyString())).thenReturn("en-US");

        MarginService marginService = mock(MarginService.class);
        when(marginService.apply(Mockito.any(), Mockito.any(), Mockito.any())).thenAnswer(
                i -> new MarginService.PriceWithMargin(i.getArgument(0), i.getArgument(0), null, BigDecimal.ZERO));

        CustomsValuationService aduanas = mock(CustomsValuationService.class);
        lenient().when(aduanas.perArticleFeeUsdCents(any())).thenReturn(0);

        // Una promoción que SIEMPRE parte el precio por la mitad, respetando el suelo. Así, si alguna
        // vía deja pasar la rebaja a un precio de mayoreo, el importe se cae a la mitad y la prueba lo
        // ve; con un mock que no descuenta nada, el fallo pasaría inadvertido.
        PromotionService promociones = mock(PromotionService.class);
        lenient().when(promociones.applyAutomatic(any(), any(), any())).thenAnswer(i -> {
            BigDecimal precio = i.getArgument(1);
            BigDecimal suelo = i.getArgument(2);
            if (precio == null) {
                return PromotionService.Discounted.none(null);
            }
            BigDecimal rebajado = precio.multiply(RESTO_TRAS_LA_REBAJA).setScale(2, RoundingMode.HALF_UP);
            if (suelo != null && rebajado.compareTo(suelo) < 0) {
                rebajado = suelo;
            }
            return new PromotionService.Discounted(precio, rebajado, new BigDecimal("10"), "Campaña de prueba",
                    UUID.randomUUID());
        });

        pricingService = new PricingService(currencyService, promociones, marginService, aduanas);
    }

    @Test
    @DisplayName("el precio de un tramo de mayoreo no se rebaja")
    void elPrecioDeMayoreoNoSeRebaja() {
        ProductEntity p = producto();
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00")),
                tramo(p, 100, new BigDecimal("8.00")));

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0), 100, escalera);

        // Proporción 8/10 sobre la variante de 10,00 = 8,00, más los 4,00 de IVA del proveedor.
        // Con la campaña encima saldrían 10,80: un 10 % regalado sobre un precio que ya es el de coste
        // por volumen.
        assertThat(precio.displayAmount()).isEqualByComparingTo("12.00");
        assertThat(precio.discounted()).isFalse();
        assertThat(precio.discountPercent()).isNull();
        assertThat(precio.promotionName()).isNull();
        // El tachado también desaparece: enseñar un «antes» sin rebaja detrás es anunciar una promoción
        // que el cobro no aplica, y es justo lo que el escaparate pintaba.
        assertThat(precio.originalFormatted()).isNull();
    }

    @Test
    @DisplayName("el primer tramo es precio unitario y sí se rebaja")
    void elPrimerTramoSiSeRebaja() {
        // EL control. Sin él, la forma fácil de cumplir la regla es apagar la rebaja en cuanto el
        // producto tenga tabla de cantidades, y como el primer escalón lo tienen TODOS los productos
        // eso apagaría las campañas del catálogo entero sin un solo error.
        ProductEntity p = producto();
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00")),
                tramo(p, 100, new BigDecimal("8.00")));

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0), 1, escalera);

        // 10,00 de la variante + 4,00 de IVA = 14,00, menos el 10 % = 12,60.
        assertThat(precio.displayAmount()).isEqualByComparingTo("12.60");
        assertThat(precio.discounted()).isTrue();
        assertThat(precio.discountPercent()).isEqualTo(10);
    }

    @Test
    @DisplayName("sin tabla de cantidades, el precio se rebaja como siempre")
    void sinTablaDeCantidadesSeRebaja() {
        // La inmensa mayoría de las compras son de pocas unidades y muchas fichas no tienen escalera:
        // ese camino no puede cambiar por esto.
        ProductEntity p = producto();

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0), 500, List.of());

        assertThat(precio.displayAmount()).isEqualByComparingTo("12.60");
        assertThat(precio.discounted()).isTrue();
    }

    @Test
    @DisplayName("un tramo de mayoreo que solo cambia el recargo tampoco se rebaja")
    void unTramoDeMayoreoSoloConRecargoTampocoSeRebaja() {
        // El borde que se escapa si la regla se escribe sobre la proporción en vez de sobre el escalón:
        // un tramo sin precio propio pero con su recargo sigue siendo una venta al por mayor.
        ProductEntity p = producto();
        ProductPriceTierEntity mayorista = tramo(p, 100, null);
        mayorista.setSurchargeCny(new BigDecimal("1.00"));
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00")), mayorista);

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0), 100, escalera);

        // 10,00 de la variante + 4,00 de IVA + 1,00 de recargo del tramo, sin rebajar.
        assertThat(precio.displayAmount()).isEqualByComparingTo("15.00");
        assertThat(precio.discounted()).isFalse();
    }

    @Test
    @DisplayName("esPrecioDeMayoreo distingue el primer escalón de los demás")
    void esPrecioDeMayoreoDistingueElPrimerEscalon() {
        // Lo consultan el checkout y el pedido para dejar las líneas de mayoreo fuera del cupón y del
        // descuento de referido. Si esto se equivocara, los descuentos volverían por esa puerta.
        ProductEntity p = producto();
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00")),
                tramo(p, 100, new BigDecimal("8.00")));

        assertThat(pricingService.esPrecioDeMayoreo(escalera, 1)).isFalse();
        assertThat(pricingService.esPrecioDeMayoreo(escalera, 99)).isFalse();
        assertThat(pricingService.esPrecioDeMayoreo(escalera, 100)).isTrue();
        assertThat(pricingService.esPrecioDeMayoreo(List.of(), 1000)).isFalse();
        assertThat(pricingService.esPrecioDeMayoreo(null, 1000)).isFalse();
    }

    private static ProductEntity producto() {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("X").basePrice(new BigDecimal("10.00"))
                .currency("CNY").surchargeCny(BigDecimal.ZERO).ivaCny(new BigDecimal("4.00")).build();
        ProductVariantEntity v = ProductVariantEntity.builder().product(p).sku("SKU-1").price(new BigDecimal("10.00"))
                .stock(5).options(Map.of()).active(true).build();
        p.setVariants(List.of(v));
        return p;
    }

    private static ProductPriceTierEntity tramo(ProductEntity p, int minQty, BigDecimal unitPrice) {
        return ProductPriceTierEntity.builder().product(p).minQty(minQty).unitPrice(unitPrice).build();
    }
}
