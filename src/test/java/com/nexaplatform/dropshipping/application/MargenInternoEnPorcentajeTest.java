package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
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
 * El margen interno, en porcentaje sobre el coste del proveedor.
 *
 * <p>Regla del titular (25-sep-2026): la línea del desglose que decía «IVA» pasa a ser el margen
 * interno y se guarda en PORCENTAJE.
 *
 * <p><b>Por qué se cambió.</b> Nunca fue el IVA de China —ese es el 13 %—: valía EXACTAMENTE el 50 %
 * de la base en los 263 productos del catálogo, o sea margen nuestro con el nombre de otra cosa. Y al
 * guardarse como importe fijo en yuanes, cada vez que cambiaba el coste del proveedor había que
 * rehacerlo a mano o se quedaba desfasado sin que nada avisara.
 *
 * <p><b>Lo que NO puede cambiar es el precio.</b> Cambiar el nombre y la unidad de un concepto no
 * puede mover lo que paga nadie: con el 50 % de siempre, el total tiene que salir idéntico al que
 * salía con el importe.
 */
class MargenInternoEnPorcentajeTest {

    private PricingService pricingService;

    @BeforeEach
    void setup() {
        CurrencyRateService currencyService = mock(CurrencyRateService.class);
        when(currencyService.toUsd(Mockito.any(), Mockito.anyString())).thenAnswer(i -> i.getArgument(0));
        when(currencyService.usdToDisplay(Mockito.any())).thenAnswer(i -> i.getArgument(0));
        lenient().when(currencyService.symbolOf(Mockito.anyString())).thenReturn("$");
        lenient().when(currencyService.localeOf(Mockito.anyString())).thenReturn("en-US");

        MarginService marginService = mock(MarginService.class);
        // Sin margen comercial encima: así lo que se mide es EXACTAMENTE lo que aporta el margen
        // interno, sin que el factor de la regla de precio lo enmascare.
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
    @DisplayName("con el 50 % de siempre, el precio sale idéntico al que daba el importe")
    void conElCincuentaPorCientoElPrecioNoSeMueve() {
        // El caso real del catálogo: coste 18, «IVA» 9, envío 10 → 37. Con el 50 % en porcentaje
        // tiene que salir lo mismo, o el cambio de nombre habría movido el precio de 263 productos.
        ProductEntity p = producto(new BigDecimal("50"));

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0));

        assertThat(precio.displayAmount()).isEqualByComparingTo("37");
    }

    @Test
    @DisplayName("el margen sigue al coste: al subir el precio del proveedor, sube solo")
    void elMargenSigueAlCoste() {
        // Esta es la razón de guardarlo en porcentaje. Con el importe fijo, un coste que sube de 18 a
        // 30 dejaba el margen clavado en 9 —un 30 % en vez del 50 %— y nadie se enteraba.
        ProductEntity p = producto(new BigDecimal("50"));
        p.getVariants().get(0).setPrice(new BigDecimal("30"));

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0));

        // 30 de coste + 15 de margen (50 %) + 10 de envío = 55.
        assertThat(precio.displayAmount()).isEqualByComparingTo("55");
    }

    @Test
    @DisplayName("sin porcentaje, no se inventa ninguno: aporta cero, como el importe nulo de antes")
    void sinPorcentajeAportaCero() {
        // Primero se escribió al revés —un 50 % por defecto cuando falta el dato— y la batería lo
        // tumbó: ocho pruebas de precio subieron un 50 % de golpe. Tenían razón. Renombrar un concepto
        // no puede encarecer nada, y un porcentaje inventado para los productos a los que les falta el
        // dato es exactamente eso, pero en silencio. El importador exige el campo, así que un producto
        // real siempre lo trae; el que no lo traiga se comporta como antes con el importe a nulo.
        ProductEntity p = producto(null);

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0));

        // 18 de coste + 0 + 10 de envío = 28.
        assertThat(precio.displayAmount()).isEqualByComparingTo("28");
    }

    @Test
    @DisplayName("un margen del cero por ciento se respeta: es una decisión, no un dato que falte")
    void ceroPorCientoSeRespeta() {
        ProductEntity p = producto(BigDecimal.ZERO);

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0));

        // 18 de coste + 0 de margen + 10 de envío = 28.
        assertThat(precio.displayAmount()).isEqualByComparingTo("28");
    }

    @Test
    @DisplayName("con tabla de cantidades, el margen baja en la misma proporción que el coste")
    void conTramoElMargenBajaConElCoste() {
        // Es lo que se espera de un PORCENTAJE y no pasaba con el importe fijo: al bajar el coste por
        // volumen, el margen absoluto bajaba con él solo si alguien lo recalculaba.
        ProductEntity p = producto(new BigDecimal("50"));
        var escalera = List.of(
                com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity.builder()
                        .product(p).minQty(1).unitPrice(new BigDecimal("18")).build(),
                com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity.builder()
                        .product(p).minQty(100).unitPrice(new BigDecimal("9")).build());

        PricingService.PricedAmount precio = pricingService.priceFor(p, p.getVariants().get(0), 100, escalera);

        // Coste 9 (la mitad por el tramo) + 4,50 de margen + 10 de envío = 23,50.
        assertThat(precio.displayAmount()).isEqualByComparingTo("23.50");
    }

    private static ProductEntity producto(BigDecimal margenPct) {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("X").basePrice(new BigDecimal("18"))
                .currency("CNY").surchargeCny(BigDecimal.ZERO).shippingCny(new BigDecimal("10"))
                .margenInternoPct(margenPct).build();
        ProductVariantEntity v = ProductVariantEntity.builder().product(p).sku("SKU-1").price(new BigDecimal("18"))
                .stock(5).options(Map.of()).active(true).build();
        p.setVariants(List.of(v));
        return p;
    }
}
