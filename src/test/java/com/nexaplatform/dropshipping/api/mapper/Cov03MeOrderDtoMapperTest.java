package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.out.MeOrderDetailDtoOut;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ficha del pedido del comprador.
 *
 * <p>Lo que se protege aquí es que el importe mostrado sea EXACTAMENTE el cobrado. Convertir el total de
 * una sola vez daba céntimos de menos frente al cobro real (el pedido decía 85,76 € y la pasarela había
 * cobrado 85,81 €), y un pedido ya pagado no puede re-convertirse a la tasa de hoy: el cargo del banco
 * no cambia con el tiempo.
 */
class Cov03MeOrderDtoMapperTest {

    private CurrencyRateService currency;
    private PaymentJpaRepositoryAdapter payments;
    private MeOrderDtoMapper mapper;

    @BeforeEach
    void preparaMapeador() {
        currency = mock(CurrencyRateService.class);
        payments = mock(PaymentJpaRepositoryAdapter.class);
        lenient().when(currency.usdTo(any(BigDecimal.class), anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(currency.formatDisplay(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> i.getArgument(0) + " $");
        lenient().when(payments.findByOrderIdOrderByCreatedAtDesc(any(UUID.class))).thenReturn(List.of());
        mapper = new MeOrderDtoMapper(currency, payments);
    }

    @AfterEach
    void limpiaDivisa() {
        CurrencyHolder.clear();
    }

    private static OrderItem linea(int unitCents, int cantidad) {
        OrderItem item = new OrderItem();
        item.setId(UUID.randomUUID());
        item.setProductId(UUID.randomUUID());
        item.setUnitPriceCents(unitCents);
        item.setQuantity(cantidad);
        item.setTitleSnapshot("Chaqueta");
        return item;
    }

    private static Order pedido(int envioCents, int impuestoCents, int descuentoCents, OrderItem... lineas) {
        Order o = new Order();
        o.setId(UUID.randomUUID());
        o.setOrderNumber("NX-1");
        o.setStatus(OrderStatus.PAID);
        o.setCurrency("USD");
        o.setShippingCents(envioCents);
        o.setTaxCents(impuestoCents);
        o.setDiscountCents(descuentoCents);
        o.setItems(new ArrayList<>(List.of(lineas)));
        return o;
    }

    private static PaymentEntity pago(PaymentStatus estado, BigDecimal importe, String divisa) {
        PaymentEntity p = new PaymentEntity();
        p.setStatus(estado);
        p.setSettlementAmount(importe);
        p.setSettlementCurrency(divisa);
        return p;
    }

    /* ==================== desglose ==================== */

    @Test
    void elTotalEsLaSumaDeLosComponentesYaRedondeados() {
        Order o = pedido(500, 210, 0, linea(1000, 2));

        MeOrderDetailDtoOut d = mapper.toDetailDtoOut(o);

        assertThat(d.getSubtotal()).isEqualByComparingTo("20.00");
        assertThat(d.getShipping()).isEqualByComparingTo("5.00");
        assertThat(d.getTax()).isEqualByComparingTo("2.10");
        assertThat(d.getTotal()).isEqualByComparingTo("27.10");
        assertThat(d.getTotalFormatted()).isEqualTo("27.10 $");
    }

    @Test
    void cadaLineaSeConvierteAparteYLuegoSeSuma() {
        // Convertir el total de una vez daba entre 1 y 5 céntimos menos de lo realmente cobrado, porque el
        // cobro también se liquida línea a línea.
        when(currency.usdTo(any(BigDecimal.class), anyString()))
                .thenAnswer(i -> ((BigDecimal) i.getArgument(0)).multiply(new BigDecimal("0.925")));
        Order o = pedido(0, 0, 0, linea(999, 1), linea(999, 1));

        MeOrderDetailDtoOut d = mapper.toDetailDtoOut(o);

        // 9,99 x 0,925 = 9,24075 por línea; la suma sin redondear intermedio es 18,4815 -> 18,48
        assertThat(d.getSubtotal()).isEqualByComparingTo("18.48");
        assertThat(d.getItems()).hasSize(2);
        assertThat(d.getItems().get(0).getUnitPrice()).isEqualByComparingTo("9.24075");
    }

    @Test
    void elDescuentoDeReferidoSeRestaDelTotal() {
        Order o = pedido(500, 0, 300, linea(1000, 1));

        MeOrderDetailDtoOut d = mapper.toDetailDtoOut(o);

        assertThat(d.getDiscount()).isEqualByComparingTo("3.00");
        assertThat(d.getTotal()).isEqualByComparingTo("12.00"); // 10 - 3 + 5
    }

    /* ==================== pedido ya pagado: manda lo cobrado ==================== */

    @Test
    void unPedidoYaPagadoMuestraExactamenteLoCobradoYNoLaTasaDeHoy() {
        Order o = pedido(500, 210, 0, linea(1000, 2));
        when(payments.findByOrderIdOrderByCreatedAtDesc(o.getId()))
                .thenReturn(List.of(pago(PaymentStatus.SUCCEEDED, new BigDecimal("27.15"), "USD")));

        MeOrderDetailDtoOut d = mapper.toDetailDtoOut(o);

        assertThat(d.getTotal()).isEqualByComparingTo("27.15");
        // El desglose se reescala para que siga cuadrando con el total cobrado.
        assertThat(d.getSubtotal().subtract(d.getDiscount()).add(d.getShipping()).add(d.getTax()))
                .isEqualByComparingTo(d.getTotal());
    }

    @Test
    void unPagoEnOtraMonedaNoSustituyeElTotalMostrado() {
        // El cliente está viendo el pedido en dólares pero pagó en euros: mezclar importes daría un total
        // que no es ni uno ni otro.
        Order o = pedido(0, 0, 0, linea(1000, 1));
        when(payments.findByOrderIdOrderByCreatedAtDesc(o.getId()))
                .thenReturn(List.of(pago(PaymentStatus.SUCCEEDED, new BigDecimal("9.20"), "EUR")));

        assertThat(mapper.toDetailDtoOut(o).getTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void unPagoQueNoLlegoACobrarseNoSustituyeElTotalMostrado() {
        Order o = pedido(0, 0, 0, linea(1000, 1));
        when(payments.findByOrderIdOrderByCreatedAtDesc(o.getId()))
                .thenReturn(List.of(pago(PaymentStatus.FAILED, new BigDecimal("999.99"), "USD")));

        assertThat(mapper.toDetailDtoOut(o).getTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void unPagoSinImporteLiquidadoComoElDelMonederoNoSustituyeElTotal() {
        Order o = pedido(0, 0, 0, linea(1000, 1));
        when(payments.findByOrderIdOrderByCreatedAtDesc(o.getId()))
                .thenReturn(List.of(pago(PaymentStatus.SUCCEEDED, null, "USD")));

        assertThat(mapper.toDetailDtoOut(o).getTotal()).isEqualByComparingTo("10.00");
    }

    @Test
    void unPedidoATotalCeroNoSeReescalaNiDividePorCero() {
        Order o = pedido(0, 0, 0);
        when(payments.findByOrderIdOrderByCreatedAtDesc(o.getId()))
                .thenReturn(List.of(pago(PaymentStatus.SUCCEEDED, new BigDecimal("5.00"), "USD")));

        assertThat(mapper.toDetailDtoOut(o).getTotal()).isEqualByComparingTo("0.00");
    }

    /* ==================== lista de pedidos ==================== */

    @Test
    void laListaDePedidosMuestraElMismoTotalQueLaFicha() {
        // Ver 27,10 € en la lista y 27,15 € al abrir el pedido es el tipo de desfase que hace pensar que
        // se ha cobrado de más.
        Order o = pedido(500, 210, 0, linea(1000, 2));
        when(payments.findByOrderIdOrderByCreatedAtDesc(o.getId()))
                .thenReturn(List.of(pago(PaymentStatus.SUCCEEDED, new BigDecimal("27.15"), "USD")));

        assertThat(mapper.formatOrderTotal(o)).isEqualTo(mapper.toDetailDtoOut(o).getTotalFormatted());
    }

    @Test
    void sinPagoLaListaFormateaElTotalCalculado() {
        Order o = pedido(500, 210, 100, linea(1000, 2));

        assertThat(mapper.formatOrderTotal(o)).isEqualTo("26.10 $"); // 20 - 1 + 5 + 2,10
    }

    @Test
    void unPedidoSinLineasNoRevientaAlCalcularSuTotal() {
        Order o = pedido(500, 0, 0);
        o.setItems(null);

        assertThat(mapper.formatOrderTotal(o)).isEqualTo("5.00 $");
    }

    @Test
    void sinPedidoNoHayNadaQueProyectar() {
        assertThat(mapper.toDetailDtoOut(null)).isNull();
        assertThat(mapper.formatOrderTotal(null)).isNull();
    }

    /* ==================== miniatura de la línea ==================== */

    @Test
    void laMiniaturaEsLaDelColorPedidoAntesQueCualquierOtra() {
        // La foto tiene que coincidir con lo comprado: si sale el color genérico, el cliente cree que le
        // han enviado otra cosa.
        OrderItem item = linea(1000, 1);
        item.setVariantImageUrl("https://cdn/roja.jpg");
        item.setImageUrlSnapshot("https://cdn/generica.jpg");
        item.setProductImageUrl("https://cdn/viva.jpg");
        Order o = pedido(0, 0, 0, item);

        assertThat(mapper.toDetailDtoOut(o).getItems().get(0).getImageUrl()).isEqualTo("https://cdn/roja.jpg");
    }

    @Test
    void sinFotoDeVarianteMandaLaInstantaneaDelPedido() {
        OrderItem item = linea(1000, 1);
        item.setVariantImageUrl("  ");
        item.setImageUrlSnapshot("https://cdn/generica.jpg");
        item.setProductImageUrl("https://cdn/viva.jpg");
        Order o = pedido(0, 0, 0, item);

        assertThat(mapper.toDetailDtoOut(o).getItems().get(0).getImageUrl()).isEqualTo("https://cdn/generica.jpg");
    }

    @Test
    void sinInstantaneaSeCaeALaFotoVivaDelProducto() {
        OrderItem item = linea(1000, 1);
        item.setProductImageUrl("https://cdn/viva.jpg");
        Order o = pedido(0, 0, 0, item);

        assertThat(mapper.toDetailDtoOut(o).getItems().get(0).getImageUrl()).isEqualTo("https://cdn/viva.jpg");
    }

    /* ==================== direcciones ==================== */

    @Test
    void sinDireccionGuardadaNoSeDevuelveUnBloqueVacio() {
        // Un bloque presente pero con todos los campos a nulo pinta una tarjeta de dirección en blanco.
        Order o = pedido(0, 0, 0, linea(1000, 1));

        MeOrderDetailDtoOut d = mapper.toDetailDtoOut(o);

        assertThat(d.getShippingAddress()).isNull();
        assertThat(d.getBillingAddress()).isNull();
    }

    @Test
    void laDireccionDeEnvioSeMontaConLaInstantaneaGuardadaEnElPedido() {
        Order o = pedido(0, 0, 0, linea(1000, 1));
        o.setShippingFullName("Ana Pérez");
        o.setShippingLine1("Calle Mayor 1");
        o.setShippingCity("Madrid");
        o.setShippingCountry("ES");
        o.setBillingLine1("Gran Vía 2");

        MeOrderDetailDtoOut d = mapper.toDetailDtoOut(o);

        assertThat(d.getShippingAddress().getFullName()).isEqualTo("Ana Pérez");
        assertThat(d.getShippingAddress().getCountry()).isEqualTo("ES");
        // Basta con la calle para que exista bloque de facturación (el nombre puede faltar).
        assertThat(d.getBillingAddress().getLine1()).isEqualTo("Gran Vía 2");
    }
}
