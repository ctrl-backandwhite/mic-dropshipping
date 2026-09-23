package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.CustomsValuationService;
import com.nexaplatform.dropshipping.application.service.MarginService;
import com.nexaplatform.dropshipping.application.service.MarginService.PriceWithMargin;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
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

    /**
     * El destino decide si se cobra el subsidio de arancel. Aquí se deja en un país que NO cobra derecho
     * por artículo —el caso de la mayoría del mundo— para que esa línea valga cero y no enturbie lo que
     * esta prueba mide, que es la fórmula del tramo por cantidad.
     */
    @Mock
    private CustomsValuationService customsValuation;

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
        when(currencyRateService.formatDisplay(any(), any())).thenAnswer(inv -> "$" + inv.<BigDecimal>getArgument(0));
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

        // 1,99 (base con margen) + 0,74 (IVA con margen) + 3,44 (envío con margen) = 6,17.
        //
        // Quedarse en 1,99 —solo la base— era el defecto original. Y quedarse en 3,57 —sumando el IVA y el
        // envío en crudo— era el modelo hasta el 25-ago-2026: desde esa fecha el margen se aplica sobre el
        // desembolso completo del proveedor, así que el mismo factor de 2,64 que multiplica la base
        // multiplica también sus 0,28 de IVA y sus 1,30 de porte.
        assertThat(delTramo.displayAmount()).isEqualByComparingTo(new BigDecimal("6.17"));
        assertThat(delTramo.displayAmount()).isNotEqualByComparingTo(new BigDecimal("1.99"));
        assertThat(delTramo.displayAmount()).isNotEqualByComparingTo(new BigDecimal("3.57"));
    }

    /**
     * El porte que se le paga al proveedor no se contamina con el margen, aunque el cliente lo pague con
     * él encima.
     *
     * <p>Qué se rompería en producción si esta prueba fallara: la subvención por porte repetido devolvería
     * 3,44 en vez de 1,30 por cada unidad extra, es decir, el margen del porte regalado por partida doble
     * —una aquí y otra por la vía de la ganancia sobrante—.
     */
    @Test
    void elPorteQueSeDevuelveEsElDelProveedorNoElCobrado() {
        PricedAmount cobrado = pricingService.priceFor(product, null);

        assertThat(cobrado.supplierShippingUsd()).isEqualByComparingTo(ENVIO_USD);
        assertThat(cobrado.shippingUsd()).isGreaterThan(cobrado.supplierShippingUsd());
    }

    @Test
    void unTramoDeUnProductoQueYaNoExisteNoRevienta() {
        assertThat(pricingService.priceForSupplierAmount(null, null, COSTE_CNY).displayAmount()).isNull();
    }

    /* ============ El recargo, ahora por tramo (23-sep-2026) ============ */

    /**
     * El recargo del TRAMO manda sobre el del producto.
     *
     * <p>Que se rompe en produccion si esta prueba falla: quien compra diez mil unidades paga diez mil
     * veces un cargo que solo se incurre una vez -la gestion de la compra, el manipulado, la parte fija
     * del despacho-, y lo paga justo en la tabla que le promete que comprar mas sale mas barato.
     */
    @Test
    void elRecargoDelTramoMandaSobreElDelProducto() {
        product.setSurchargeCny(new BigDecimal("3.00"));
        when(currencyRateService.toUsd(new BigDecimal("3.00"), "CNY")).thenReturn(new BigDecimal("0.4196"));
        when(currencyRateService.toUsd(new BigDecimal("0.50"), "CNY")).thenReturn(new BigDecimal("0.0699"));

        PricedAmount conElDelProducto = pricingService.priceForSupplierAmount(product, null, COSTE_CNY);
        PricedAmount conElDelTramo = pricingService.priceForSupplierAmount(product, null, COSTE_CNY,
                new BigDecimal("0.50"));

        assertThat(conElDelTramo.displayAmount()).isLessThan(conElDelProducto.displayAmount());
        // 6,17 del resto + 0,07 del recargo propio, en vez de los 0,42 del producto.
        assertThat(conElDelTramo.displayAmount()).isEqualByComparingTo(new BigDecimal("6.24"));
    }

    /**
     * Sin recargo propio, el tramo hereda el del producto. Es lo que deja intactos los tramos ya
     * cargados: la columna nueva esta vacia en todos ellos y el precio no se mueve ni un centimo.
     */
    @Test
    void unTramoSinRecargoPropioHeredaElDelProducto() {
        product.setSurchargeCny(new BigDecimal("3.00"));
        when(currencyRateService.toUsd(new BigDecimal("3.00"), "CNY")).thenReturn(new BigDecimal("0.4196"));

        PricedAmount heredado = pricingService.priceForSupplierAmount(product, null, COSTE_CNY, null);
        PricedAmount delProducto = pricingService.priceForSupplierAmount(product, null, COSTE_CNY);

        assertThat(heredado.displayAmount()).isEqualByComparingTo(delProducto.displayAmount());
    }

    /**
     * Un recargo de CERO en el tramo no es lo mismo que no tener recargo: cero quiere decir que ese
     * tramo no lleva cargo, y tiene que poder escribirse aunque el producto si lo tenga.
     */
    @Test
    void unRecargoDeCeroEnElTramoAnulaElDelProducto() {
        product.setSurchargeCny(new BigDecimal("3.00"));
        when(currencyRateService.toUsd(new BigDecimal("3.00"), "CNY")).thenReturn(new BigDecimal("0.4196"));

        PricedAmount sinCargo = pricingService.priceForSupplierAmount(product, null, COSTE_CNY, BigDecimal.ZERO);

        assertThat(sinCargo.displayAmount()).isEqualByComparingTo(new BigDecimal("6.17"));
    }

    /**
     * El envio y el arancel NO se tocan: siguen siendo uno por producto, porque esos si escalan con el
     * bulto. Cambiar el recargo del tramo no puede moverlos.
     */
    @Test
    void elRecargoPorTramoNoTocaElEnvioNiElArancel() {
        PricedAmount conRecargoPropio = pricingService.priceForSupplierAmount(product, null, COSTE_CNY,
                BigDecimal.ZERO);

        assertThat(conRecargoPropio.supplierShippingUsd()).isEqualByComparingTo(ENVIO_USD);
    }

    /* ======= El tramo por cantidad, APLICADO de verdad (23-sep-2026) ======= */

    /** La escalera del producto de esta prueba: 5,38 la unidad, 4,842 a partir de diez (un 10 % menos). */
    private static List<ProductPriceTierEntity> escalera() {
        return List.of(ProductPriceTierEntity.builder().minQty(1).maxQty(9).unitPrice(COSTE_CNY).build(),
                ProductPriceTierEntity.builder().minQty(10).unitPrice(new BigDecimal("4.842")).build());
    }

    /**
     * El cambio y el margen, para CUALQUIER importe.
     *
     * <p>Los dobles del arranque solo responden a los tres importes exactos del producto, y aqui la base
     * la calcula el propio servicio multiplicando por la proporcion del tramo: sale con otra escala y con
     * decimales que no se pueden anticipar. Se responde proporcionalmente, que es lo que hace el servicio
     * real, para que la prueba mida la REGLA y no la aritmetica del doble.
     */
    private void cambioYMargenParaCualquierImporte() {
        when(currencyRateService.toUsd(any(), eq("CNY")))
                .thenAnswer(inv -> inv.<BigDecimal>getArgument(0).multiply(new BigDecimal("0.1399")));
        when(marginService.apply(any(), any(), any())).thenAnswer(inv -> {
            BigDecimal coste = inv.getArgument(0);
            return new PriceWithMargin(coste, coste == null ? null : coste.multiply(new BigDecimal("2.6440")), null,
                    new BigDecimal("150"));
        });
    }

    /**
     * Comprar diez unidades sale mas barato POR UNIDAD que comprar una.
     *
     * <p>Que se rompia en produccion antes de esta prueba: la tabla de cantidades de la ficha anunciaba
     * la rebaja por volumen, el cliente metia diez unidades en la cesta y se le cobraba el precio de una.
     * Ni la cesta, ni la vista previa, ni el pedido consultaban los tramos: la promesa que hace vender
     * mas unidades no se cumplia justo en la pantalla del pago.
     */
    @Test
    void aPartirDeDiezUnidadesSeCobraElPrecioDelTramo() {
        cambioYMargenParaCualquierImporte();

        PricedAmount una = pricingService.priceFor(product, null, 1, escalera());
        PricedAmount diez = pricingService.priceFor(product, null, 10, escalera());

        assertThat(diez.displayAmount()).isLessThan(una.displayAmount());
    }

    /** Por debajo del primer escalon no hay descuento: se paga el precio de siempre. */
    @Test
    void conUnaSolaUnidadElPrecioNoSeMueve() {
        PricedAmount conTramos = pricingService.priceFor(product, null, 1, escalera());
        PricedAmount sinTramos = pricingService.priceFor(product, null);

        assertThat(conTramos.displayAmount()).isEqualByComparingTo(sinTramos.displayAmount());
    }

    /** Un producto sin escalera se tarifica como siempre, pase la cantidad que pase. */
    @Test
    void sinTramosLaCantidadNoCambiaElPrecio() {
        PricedAmount mil = pricingService.priceFor(product, null, 1000, List.of());

        assertThat(mil.displayAmount()).isEqualByComparingTo(pricingService.priceFor(product, null).displayAmount());
    }

    /**
     * El tramo se aplica como PROPORCION sobre el precio de la variante, no como importe absoluto.
     *
     * <p>Que se rompe en produccion si esta prueba falla: los tramos son del PRODUCTO y cada variante
     * tiene su coste; medido el 23-sep-2026, en 713 productos hay una variante mas cara que el primer
     * escalon —hasta 300 CNY por encima—. Cobrandole el importe literal del tramo, esa variante se
     * vende por debajo de coste, y precisamente en los pedidos grandes.
     */
    @Test
    void laVarianteCaraConservaSuSobreprecioAlAplicarElTramo() {
        cambioYMargenParaCualquierImporte();
        // Una variante que cuesta el doble que el encabezado de la escalera.
        ProductVariantEntity cara = new ProductVariantEntity();
        cara.setPrice(new BigDecimal("10.76"));
        cara.setActive(true);

        pricingService.priceFor(product, cara, 10, escalera());

        // La base tarificada es el 90 % de SU precio (9,684), no los 4,842 del tramo.
        ArgumentCaptor<BigDecimal> base = ArgumentCaptor.forClass(BigDecimal.class);
        verify(currencyRateService, atLeastOnce()).toUsd(base.capture(), eq("CNY"));
        assertThat(base.getAllValues())
                .anySatisfy(importe -> assertThat(importe).isEqualByComparingTo(new BigDecimal("9.684")));
        assertThat(base.getAllValues())
                .noneSatisfy(importe -> assertThat(importe).isEqualByComparingTo(new BigDecimal("4.842")));
    }

    @Test
    void elPrecioQueSeEnsenaEsElCanonicoConvertido() {
        // Componer el precio en la moneda del cliente (base, IVA y envío convertidos por separado) y
        // componerlo en dólares dan resultados que difieren en un céntimo. Con eso el escaparate
        // anunciaba 14,79 € mientras el pedido se cobraba a 14,78 €. El precio mostrado se deriva del
        // canónico, que es el que se guarda y se cobra.
        PricedAmount priced = pricingService.priceFor(product, null);

        assertThat(priced.displayAmount()).isEqualByComparingTo(currencyRateService.usdToDisplay(priced.retailUsd()));
    }
}
