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
 * El recargo propio del PRIMER tramo.
 *
 * <p>El cambio del 23-sep-2026 dice que el recargo «deja de ser uno por producto y pasa a poder
 * fijarse POR TRAMO». Estas pruebas comprueban que eso vale también para el primer escalón, que es
 * el que tienen TODOS los productos: si sólo funcionase del segundo en adelante, escribir un
 * recargo para 1-9 unidades se guardaría en la base y no se cobraría, sin ningún error.
 */
class RecargoDelPrimerTramoTest {

    private PricingService pricingService;
    private MarginService marginService;

    @BeforeEach
    void setup() {
        CurrencyRateService currencyService = mock(CurrencyRateService.class);
        when(currencyService.toUsd(Mockito.any(), Mockito.anyString())).thenAnswer(i -> i.getArgument(0));
        when(currencyService.usdToDisplay(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(currencyService.symbolOf(Mockito.anyString())).thenReturn("$");
        lenient().when(currencyService.localeOf(Mockito.anyString())).thenReturn("en-US");

        marginService = mock(MarginService.class);
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

    @Test
    @DisplayName("el recargo propio del primer tramo se cobra, no el del producto")
    void elRecargoDelPrimerTramoSeCobra() {
        // Lo que se rompería: el administrador escribe 0,80 de recargo para 1-9 unidades, el valor
        // se guarda en `product_price_tier.surcharge_cny` y el cobro sigue usando los 5,00 del
        // producto. Ningún error, ninguna traza: sólo un precio que no es el que se fijó.
        ProductEntity p = productoCon(new BigDecimal("5.00"));
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00"), new BigDecimal("0.80")),
                tramo(p, 100, new BigDecimal("8.00"), null));

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0), 1, escalera);

        // 10 de base + 0,80 del tramo = 10,80. Con el recargo del producto saldrían 15,00.
        assertThat(precio.displayAmount()).isEqualByComparingTo("10.80");
    }

    @Test
    @DisplayName("sin recargo propio, el primer tramo hereda el del producto")
    void sinRecargoPropioElPrimerTramoHereda() {
        // EL control: «nulo no es cero» también aquí. Sin esta prueba, hacer que el primer tramo
        // use siempre su columna pondría a cero el recargo de los 9.718 productos ya cargados, que
        // la tienen vacía.
        ProductEntity p = productoCon(new BigDecimal("5.00"));
        List<ProductPriceTierEntity> escalera = List.of(tramo(p, 1, new BigDecimal("10.00"), null),
                tramo(p, 100, new BigDecimal("8.00"), null));

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0), 1, escalera);

        assertThat(precio.displayAmount()).isEqualByComparingTo("15.00");
    }

    @Test
    @DisplayName("el envío de la variante manda sobre el del producto")
    void elEnvioDeLaVarianteMandaSobreElDelProducto() {
        // El envío chino se calcula por tramos de PESO y cada talla pesa lo suyo: la M paga 10 y la
        // XL 16. Sin esto el campo llega a la base, se pinta en el panel y el cobro sigue usando el
        // del producto — el importe existe y no se usa, que es la forma más cara de fallar porque
        // parece que está hecho.
        ProductEntity p = productoCon(BigDecimal.ZERO);
        p.setShippingCny(new BigDecimal("6"));
        ProductVariantEntity pesada = p.getVariants().get(0);
        pesada.setShippingCny(new BigDecimal("16"));

        PricingService.PricedAmount precio = pricingService.priceFor(p, pesada);

        // 10 de base + 16 de envío de la variante = 26. Con el del producto saldrían 16.
        assertThat(precio.displayAmount()).isEqualByComparingTo("26");
    }

    @Test
    @DisplayName("una variante sin envío propio usa el del producto")
    void unaVarianteSinEnvioPropioUsaElDelProducto() {
        // EL control: «nulo no es cero», igual que con el recargo del tramo. Las 225.959 variantes
        // ya cargadas tienen la columna vacía, y darles envío cero las pondría a portes gratis.
        ProductEntity p = productoCon(BigDecimal.ZERO);
        p.setShippingCny(new BigDecimal("6"));

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0));

        assertThat(precio.displayAmount()).isEqualByComparingTo("16");
    }

    private static ProductEntity productoCon(BigDecimal recargoDelProducto) {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("X").basePrice(new BigDecimal("10.00"))
                .currency("CNY").surchargeCny(recargoDelProducto).build();
        ProductVariantEntity v = ProductVariantEntity.builder().product(p).sku("SKU-1").price(new BigDecimal("10.00"))
                .stock(5).options(Map.of()).active(true).build();
        p.setVariants(List.of(v));
        return p;
    }

    private static ProductPriceTierEntity tramo(ProductEntity p, int minQty, BigDecimal unitPrice,
            BigDecimal surcharge) {
        return ProductPriceTierEntity.builder().product(p).minQty(minQty).unitPrice(unitPrice).surchargeCny(surcharge)
                .build();
    }
}
