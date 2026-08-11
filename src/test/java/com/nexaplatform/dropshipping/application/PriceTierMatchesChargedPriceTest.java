package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.MarginService.PriceWithMargin;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * El precio de un tramo por cantidad es el que se COBRA por esa cantidad.
 *
 * <p>Se separaron: el tramo se calculaba aparte —coste → USD → margen— y ahí se quedaba, sin el IVA ni
 * el envío que sí lleva el precio real. Con los datos de un producto de catálogo la ficha anunciaba
 * «2+ → 1,99 $» y el carrito cobraba 3,57 $ la unidad: un 79 % por encima de lo prometido en la tabla
 * de cantidades, que es exactamente el motivo por el que alguien compra más unidades.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("El precio del tramo por cantidad coincide con el que se cobra")
class PriceTierMatchesChargedPriceTest {

    @Mock
    private CurrencyRateService currencyRateService;
    @Mock
    private MarginService marginService;

    /**
     * Sin rebajas: esta prueba comprueba que el precio del tramo por cantidad sale con la misma
     * fórmula que el que se cobra. Una promoción activa movería ambos y taparía justamente eso.
     */
    @Mock
    PromotionService promotionService;

    @InjectMocks
    private PricingService pricingService;

    private ProductEntity product;

    /** Coste del proveedor y del tramo: el mismo, como en el producto que destapó el fallo. */
    private static final BigDecimal COSTE_CNY = new BigDecimal("5.38");
    private static final BigDecimal COSTE_USD = new BigDecimal("0.7526");
    private static final BigDecimal CON_MARGEN_USD = new BigDecimal("1.9900");
    private static final BigDecimal IVA_CNY = new BigDecimal("2.00");
    private static final BigDecimal IVA_USD = new BigDecimal("0.2798");
    private static final BigDecimal ENVIO_CNY = new BigDecimal("9.30");
    private static final BigDecimal ENVIO_USD = new BigDecimal("1.3010");

    @BeforeEach
    void setUp() {
        // El motor de promociones devuelve el precio intacto: aquí no se miden rebajas.
        when(promotionService.applyAutomatic(any(), any(), any())).thenAnswer(inv -> {
            java.math.BigDecimal precio = inv.getArgument(1);
            return new PromotionService.Discounted(precio, precio, java.math.BigDecimal.ZERO, null, null);
        });
        product = new ProductEntity();
        product.setBasePrice(COSTE_CNY);
        product.setCurrency("CNY");
        product.setIvaCny(IVA_CNY);
        product.setShippingCny(ENVIO_CNY);

        when(currencyRateService.toUsd(COSTE_CNY, "CNY")).thenReturn(COSTE_USD);
        when(currencyRateService.toUsd(IVA_CNY, "CNY")).thenReturn(IVA_USD);
        when(currencyRateService.toUsd(ENVIO_CNY, "CNY")).thenReturn(ENVIO_USD);
        when(marginService.apply(eq(COSTE_USD), any(), any()))
                .thenReturn(new PriceWithMargin(COSTE_USD, CON_MARGEN_USD, null, new BigDecimal("150")));
        // usdToDisplay en la misma moneda (USD): dos decimales, como hace el servicio real.
        when(currencyRateService.usdToDisplay(any())).thenAnswer(inv -> {
            BigDecimal v = inv.getArgument(0);
            return v == null ? null : v.setScale(2, java.math.RoundingMode.HALF_UP);
        });
        when(currencyRateService.formatDisplay(any(), any()))
                .thenAnswer(inv -> "$" + inv.<BigDecimal>getArgument(0));
        when(currencyRateService.symbolOf(any())).thenReturn("$");
    }

    @Test
    void elTramoSeTarificaConLaMismaFormulaQueElPrecioQueSeCobra() {
        PricedAmount cobrado = pricingService.priceFor(product, null);
        PricedAmount delTramo = pricingService.priceForSupplierAmount(product, null, COSTE_CNY);

        assertThat(delTramo.displayAmount()).isEqualByComparingTo(cobrado.displayAmount());
        assertThat(delTramo.displayFormatted()).isEqualTo(cobrado.displayFormatted());
    }

    @Test
    void elTramoIncluyeElIvaYElEnvio() {
        PricedAmount delTramo = pricingService.priceForSupplierAmount(product, null, COSTE_CNY);

        // 1,99 (base con margen) + 0,28 (IVA) + 1,30 (envío) = 3,57. Quedarse en 1,99 era el defecto.
        assertThat(delTramo.displayAmount()).isEqualByComparingTo(new BigDecimal("3.57"));
        assertThat(delTramo.displayAmount()).isNotEqualByComparingTo(new BigDecimal("1.99"));
    }

    @Test
    void unTramoDeUnProductoQueYaNoExisteNoRevienta() {
        assertThat(pricingService.priceForSupplierAmount(null, null, COSTE_CNY).displayAmount()).isNull();
    }

    @Test
    void elPrecioQueSeEnsenaEsElCanonicoConvertido() {
        // Componer el precio en la moneda del cliente (base, IVA y envío convertidos por separado) y
        // componerlo en dólares dan resultados que difieren en un céntimo. Con eso el escaparate
        // anunciaba 14,79 € mientras el pedido se cobraba a 14,78 €. El precio mostrado se deriva del
        // canónico, que es el que se guarda y se cobra.
        PricedAmount priced = pricingService.priceFor(product, null);

        assertThat(priced.displayAmount())
                .isEqualByComparingTo(currencyRateService.usdToDisplay(priced.retailUsd()));
    }
}
