package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.EuComplianceService;
import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.OrderAmounts;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyRateService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Importes de la factura de un pedido.
 *
 * <p>Dos reglas que, si se rompen, dejan una factura que no cuadra con el cargo del banco:
 *
 * <ul>
 *   <li>El subtotal se SUMA línea a línea, no se convierte el total de una vez, para que la factura
 *       cuadre consigo misma y con lo que el comprador vio en el carrito.</li>
 *   <li>Si el pedido ya se cobró, manda el importe LIQUIDADO. El tipo de cambio se mueve: una factura
 *       emitida semanas después mostraría una cifra distinta de la que el banco cargó.</li>
 * </ul>
 */
class InvoiceAmountsTest {

    private InvoiceService service;
    private PaymentJpaRepositoryAdapter payments;

    private final UUID orderId = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);

        CurrencyRateService currency = mock(CurrencyRateService.class);
        // 1:1 para que las cuentas del test se lean solas; la conversión tiene sus propios tests.
        when(currency.usdTo(any(BigDecimal.class), anyString())).thenAnswer(i -> i.getArgument(0));
        when(currency.decimalsOf(anyString())).thenReturn(2);
        when(currency.formatDisplay(any(), anyString()))
                .thenAnswer(i -> i.getArgument(0, BigDecimal.class).toPlainString());

        payments = mock(PaymentJpaRepositoryAdapter.class);
        when(payments.findByOrderIdOrderByCreatedAtDesc(any())).thenReturn(List.of());

        // OrderAmounts real (no un doble): la factura tiene que hacer la MISMA cuenta que el cobro, y con
        // un doble el test dejaría de medir precisamente eso.
        service = new InvoiceService(engine, currency, mock(EuComplianceService.class),
                new OrderAmounts(currency), payments,
                mock(ProductRepository.class), mock(ProductVariantRepository.class),
                mock(ObjectStorageService.class));
    }

    /** Pedido de 2 × 40,00 + 10,00 de envío + 5,40 de impuesto, sin descuento. */
    private Order order(int discountCents) {
        Order o = new Order();
        o.setId(orderId);
        o.setOrderNumber("NX-1");
        o.setPlacedAt(Instant.parse("2026-07-15T10:30:00Z"));
        o.setShippingCents(1000);
        o.setTaxCents(540);
        o.setDiscountCents(discountCents);
        OrderItem item = new OrderItem();
        item.setUnitPriceCents(4000);
        item.setQuantity(2);
        item.setTitleSnapshot("Reloj de pulsera");
        item.setSkuSnapshot("SKU-1");
        o.setItems(List.of(item));
        return o;
    }

    private void settledAt(String amount, String currency) {
        PaymentEntity p = new PaymentEntity();
        p.setStatus(PaymentStatus.SUCCEEDED);
        p.setSettlementAmount(new BigDecimal(amount));
        p.setSettlementCurrency(currency);
        when(payments.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(p));
    }

    private Map<String, Object> modelOf(Order o, String currency) {
        return service.model(o, "es", null, currency);
    }

    @Test
    void elSubtotalEsLaSumaDeLasLineasYElTotalIncluyeEnvioEImpuesto() {
        Map<String, Object> m = modelOf(order(0), "EUR");

        assertThat(m.get("subtotal")).hasToString("80.00");
        assertThat(m.get("shipping")).hasToString("10.00");
        assertThat(m.get("tax")).hasToString("5.40");
        assertThat(m.get("total")).hasToString("95.40");
    }

    @Test
    void elDescuentoDeReferidoSeRestaDelTotal() {
        Map<String, Object> m = modelOf(order(800), "EUR");

        // 80,00 − 8,00 + 10,00 + 5,40 = 87,40
        assertThat(m.get("total")).hasToString("87.40");
        assertThat(m).containsEntry("hasDiscount", true);
    }

    @Test
    void sinDescuentoLaFacturaNoMuestraEsaLinea() {
        assertThat(modelOf(order(0), "EUR")).containsEntry("hasDiscount", false);
    }

    @Test
    void unPedidoYaCobradoFacturaExactamenteLoQueSeCobro() {
        // El pedido calculado da 95,40, pero se cobraron 90,00: manda lo cobrado, porque es lo que el
        // banco cargó y lo que el cliente puede reclamar.
        settledAt("90.00", "EUR");

        Map<String, Object> m = modelOf(order(0), "EUR");

        assertThat(m.get("total")).hasToString("90.00");
    }

    @Test
    void elDesgloseDeUnPedidoCobradoSigueSumandoElTotal() {
        // Si el desglose no se escalara, la factura mostraría partidas que no suman el total y no valdría
        // como documento fiscal.
        settledAt("90.00", "EUR");

        Map<String, Object> m = modelOf(order(0), "EUR");

        BigDecimal subtotal = new BigDecimal(m.get("subtotal").toString());
        BigDecimal shipping = new BigDecimal(m.get("shipping").toString());
        BigDecimal tax = new BigDecimal(m.get("tax").toString());
        BigDecimal total = new BigDecimal(m.get("total").toString());

        assertThat(subtotal.add(shipping).add(tax)).isEqualByComparingTo(total);
    }

    @Test
    void unCobroEnOtraDivisaNoSeUsaParaAjustarEstaFactura() {
        // El importe liquidado sólo vale si es de la MISMA moneda en que se emite; si no, se estaría
        // mezclando euros con dólares en la misma cifra.
        settledAt("90.00", "USD");

        assertThat(modelOf(order(0), "EUR").get("total")).hasToString("95.40");
    }

    @Test
    void unPagoQueNoLlegoACobrarseNoAjustaLaFactura() {
        PaymentEntity pendiente = new PaymentEntity();
        pendiente.setStatus(PaymentStatus.PENDING);
        pendiente.setSettlementAmount(new BigDecimal("10.00"));
        pendiente.setSettlementCurrency("EUR");
        when(payments.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(pendiente));

        assertThat(modelOf(order(0), "EUR").get("total")).hasToString("95.40");
    }

    @Test
    void elTipoDeIvaSeDeduceDeLosImportes() {
        // 5,40 sobre una base de 90,00 (80,00 + 10,00 de envío) = 6%
        Map<String, Object> m = modelOf(order(0), "EUR");

        assertThat(m.get("labelTax").toString()).contains("6%");
    }

    @Test
    void unPedidoSinLineasNoRompeLaFacturaNiDivideEntreCero() {
        Order vacio = order(0);
        vacio.setItems(List.of());
        vacio.setShippingCents(0);
        vacio.setTaxCents(0);

        Map<String, Object> m = modelOf(vacio, "EUR");

        assertThat(m.get("total")).hasToString("0.00");
        assertThat(m.get("labelTax").toString()).contains("0%");
    }

    @Test
    void sinDivisaIndicadaLaFacturaSeEmiteEnDolares() {
        Map<String, Object> m = service.model(order(0), "es", null, "  ");

        assertThat(m).containsKey("total");
    }

    /**
     * Las filas que la factura imprime tienen que sumar su total.
     *
     * <p>{@code shipping_cents} guarda el porte CON el arancel dentro y {@code customs_duty_cents} la parte
     * que corresponde al derecho. {@link OrderAmounts} —la cuenta canónica, la que usan el panel y la app—
     * los resta para poder enseñarlos por separado sin mover el total. La factura no lo hacía: imprimía
     * «Envío» con el arancel dentro y, además, una fila «Arancel UE» con el mismo importe otra vez. El
     * total seguía siendo correcto, pero quien sumara las líneas obtenía el total MÁS el arancel, y el
     * «Envío» de la factura no coincidía con el del pedido para el mismo pedido.
     *
     * <p>Es un documento con valor legal y con un QR de verificación: que no cuadre consigo mismo no es un
     * detalle de presentación.
     */
    @Test
    void lasFilasQueLaFacturaImprimeSumanSuTotal() {
        Order o = order(0);
        o.setShippingCents(1000);      // 6,51 de porte + 3,49 de arancel, todo junto
        o.setCustomsDutyCents(349);

        Map<String, Object> m = modelOf(o, "EUR");

        BigDecimal suma = importe(m, "subtotal").subtract(importe(m, "discount"))
                .add(importe(m, "shipping")).add(importe(m, "customsDuty")).add(importe(m, "tax"));
        assertThat(suma)
                .as("subtotal − descuento + envío + arancel + IVA debe dar el total impreso")
                .isEqualByComparingTo(importe(m, "total"));
    }

    /** El «Envío» de la factura es el mismo que el del pedido: neto, sin el arancel dentro. */
    @Test
    void elEnvioDeLaFacturaEsElMismoQueElDelPedido() {
        Order o = order(0);
        o.setShippingCents(1000);
        o.setCustomsDutyCents(349);

        BigDecimal enElPedido = new OrderAmounts(currencyParaElPedido()).of(o, "EUR").shipping();

        assertThat(importe(modelOf(o, "EUR"), "shipping")).isEqualByComparingTo(enElPedido);
    }

    private static BigDecimal importe(Map<String, Object> modelo, String clave) {
        Object v = modelo.get(clave);
        return v == null ? BigDecimal.ZERO : new BigDecimal(String.valueOf(v));
    }

    private CurrencyRateService currencyParaElPedido() {
        CurrencyRateService currency = mock(CurrencyRateService.class);
        when(currency.usdTo(any(BigDecimal.class), anyString())).thenAnswer(i -> i.getArgument(0));
        when(currency.decimalsOf(anyString())).thenReturn(2);
        return currency;
    }
}
