package com.nexaplatform.dropshipping.api;

import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CartQuoteItemIn;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CartQuoteOut;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * El peso que se le enseña al comprador en el carrito.
 *
 * <p>Es el peso <b>neto de la variante</b> —lo que pesa el artículo, no lo que factura el transportista—
 * y sale del dato real de 1688. <b>Nunca de un respaldo inventado</b>: el backend tiene un 500 g por
 * defecto para poder cotizar un envío, pero enseñárselo al cliente como el peso de su compra sería darle
 * un número que nadie ha medido. Hoy el 15 % de las variantes no lo tiene, así que este caso no es raro:
 * es uno de cada siete.
 *
 * <p>Cuando falta, la línea lo dice y el total se enseña como parcial. Un total que suma en silencio solo
 * lo que conoce es peor que no darlo: el comprador cree que ese es el peso de su paquete.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CartQuotePesoTest {

    private static final UUID PRODUCTO = UUID.randomUUID();
    private static final UUID VARIANTE = UUID.randomUUID();

    @Mock
    ProductRepository productRepository;
    @Mock
    PricingService pricingService;
    @Mock
    CurrencyRateService currencyService;
    @Mock
    OrderAmounts orderAmounts;
    @Mock
    CatalogStorefrontReadService storefrontRead;

    @InjectMocks
    StorefrontCatalogController controller;

    @BeforeEach
    void precioCualquiera() {
        when(pricingService.displayCurrencyCode()).thenReturn("EUR");
        when(pricingService.displayCurrencySymbol()).thenReturn("€");
        when(pricingService.priceFor(any(), any())).thenReturn(new PricedAmount(new BigDecimal("1.00"),
                new BigDecimal("10.00"), new BigDecimal("10.00"), "EUR", "€", "10,00 €", null, null,
                new BigDecimal("8.00"), BigDecimal.ZERO, BigDecimal.ZERO, "", "", ""));
        when(orderAmounts.lineSubtotal(any(), anyInt(), anyString())).thenReturn(new BigDecimal("10.00"));
        when(currencyService.formatDisplay(any(), anyString())).thenReturn("10,00 €");
    }

    @Test
    void elPesoEsElNETODeLaVarianteYSeMultiplicaPorLaCantidad() {
        catalogoCon(producto(500, variante(320)));

        CartQuoteOut q = controller.cartQuote(List.of(new CartQuoteItemIn(PRODUCTO, VARIANTE, 3)));

        assertThat(q.items()).singleElement().extracting(l -> l.weightGrams()).isEqualTo(320);
        assertThat(q.totalWeightGrams()).isEqualTo(960);
        assertThat(q.weightIncomplete()).isFalse();
    }

    @Test
    void sinPesoEnLaVarianteSeUsaElDelPRODUCTO() {
        // Sigue siendo un peso declarado en 1688, solo que de la ficha y no del SKU. Es dato real.
        catalogoCon(producto(500, variante(null)));

        CartQuoteOut q = controller.cartQuote(List.of(new CartQuoteItemIn(PRODUCTO, VARIANTE, 2)));

        assertThat(q.items()).singleElement().extracting(l -> l.weightGrams()).isEqualTo(500);
        assertThat(q.totalWeightGrams()).isEqualTo(1000);
    }

    @Test
    void sinPesoEnNINGUNODeLosDosLaLineaLoDiceYElTOTALSeMarcaIncompleto() {
        // El caso que hay que hacer visible: nada de 500 g de respaldo, nada de sumar en silencio.
        catalogoCon(producto(null, variante(null)));

        CartQuoteOut q = controller.cartQuote(List.of(new CartQuoteItemIn(PRODUCTO, VARIANTE, 2)));

        assertThat(q.items()).singleElement().extracting(l -> l.weightGrams()).isNull();
        assertThat(q.totalWeightGrams()).isZero();
        assertThat(q.weightIncomplete()).isTrue();
    }

    @Test
    void elTotalSumaLoConocidoYAvisaDeQueFaltaAlgo() {
        // Un carrito mixto: lo que se sabe se suma, lo que no se declara como incompleto. Así el «desde
        // 640 g» de la pantalla es cierto y el comprador ve que hay un dato pendiente.
        UUID otro = UUID.randomUUID();
        ProductEntity conPeso = producto(null, variante(320));
        ProductEntity sinPeso = ProductEntity.builder().weightGrams(null).variants(List.of()).build();
        sinPeso.setId(otro);
        when(productRepository.findById(PRODUCTO)).thenReturn(Optional.of(conPeso));
        when(productRepository.findById(otro)).thenReturn(Optional.of(sinPeso));

        CartQuoteOut q = controller.cartQuote(List.of(new CartQuoteItemIn(PRODUCTO, VARIANTE, 2),
                new CartQuoteItemIn(otro, null, 1)));

        assertThat(q.totalWeightGrams()).isEqualTo(640);
        assertThat(q.weightIncomplete()).isTrue();
    }

    @Test
    void unPesoDeCEROCuentaComoQueNoLoHay() {
        // En la base el «sin peso» es NULL, pero un cero colado por una carga vieja no puede pasar por
        // peso real: un artículo no pesa cero gramos.
        catalogoCon(producto(0, variante(0)));

        CartQuoteOut q = controller.cartQuote(List.of(new CartQuoteItemIn(PRODUCTO, VARIANTE, 1)));

        assertThat(q.items()).singleElement().extracting(l -> l.weightGrams()).isNull();
        assertThat(q.weightIncomplete()).isTrue();
    }

    @Test
    void elCarritoVacioNoMarcaNadaComoIncompleto() {
        CartQuoteOut q = controller.cartQuote(List.of());

        assertThat(q.totalWeightGrams()).isZero();
        assertThat(q.weightIncomplete()).isFalse();
    }

    private void catalogoCon(ProductEntity p) {
        when(productRepository.findById(PRODUCTO)).thenReturn(Optional.of(p));
    }

    private static ProductVariantEntity variante(Integer pesoNeto) {
        ProductVariantEntity v = ProductVariantEntity.builder().weightGrams(pesoNeto).build();
        v.setId(VARIANTE);
        return v;
    }

    private static ProductEntity producto(Integer pesoNeto, ProductVariantEntity v) {
        ProductEntity p = ProductEntity.builder().weightGrams(pesoNeto).variants(List.of(v)).build();
        p.setId(PRODUCTO);
        return p;
    }
}
