package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.InvoiceService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.usecase.CustomerSubscriptionUseCase;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.stripe.exception.StripeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Descarga y envío de la FACTURA de un pedido.
 *
 * <p>Dos cosas tienen que cuadrar siempre: el <b>idioma</b> (los títulos se re-traducen, así que pedir el
 * detalle en un idioma y pintar la plantilla en otro deja la factura mezclada) y la <b>moneda</b>, que
 * debe ser la que el cliente pagó de verdad — una factura en otra divisa no cuadra con el cargo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov04InvoiceControllerTest {

    @Mock
    OrderUseCase orderUseCase;
    @Mock
    CustomerSubscriptionUseCase customerSubscriptionUseCase;
    @Mock
    InvoiceService invoiceService;
    @Mock
    UserRepository userRepository;
    @Mock
    PaymentJpaRepositoryAdapter paymentRepository;
    @Mock
    OrderEmailService orderEmailService;

    @InjectMocks
    InvoiceController controller;

    private UUID userId;
    private UUID orderId;
    private Order pedido;
    private Authentication auth;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        orderId = UUID.randomUUID();
        pedido = Order.builder().id(orderId).orderNumber("NX-100").userId(userId).currency("USD").build();
        auth = mock(Authentication.class);
        when(auth.getName()).thenReturn(userId.toString());
        when(invoiceService.renderPdf(any(Order.class), anyString(), anyString())).thenReturn("%PDF".getBytes());
        when(orderUseCase.getMyOrderDetail(eq(userId), eq(orderId), anyString())).thenReturn(pedido);
        when(orderUseCase.getAdminOrderDetail(eq(orderId), any())).thenReturn(pedido);
    }

    private User usuarioConIdioma(String idioma) {
        User u = User.builder().id(userId).email("a@test").language(idioma).build();
        when(userRepository.getById(userId)).thenReturn(u);
        return u;
    }

    @Test
    void laFacturaSeGeneraEnElIdiomaDelUsuarioCuandoNoSePideUnoConcreto() {
        // Si el detalle se pidiera en un idioma y la plantilla en otro, la factura saldría mezclada.
        usuarioConIdioma("fr");

        controller.myInvoice(auth, orderId, null);

        verify(orderUseCase).getMyOrderDetail(userId, orderId, "fr");
        verify(invoiceService).renderPdf(pedido, "fr", "USD");
    }

    @Test
    void elIdiomaPedidoPorQueryMandaSobreElDelUsuario() {
        usuarioConIdioma("fr");

        controller.myInvoice(auth, orderId, "de");

        verify(orderUseCase).getMyOrderDetail(userId, orderId, "de");
    }

    @Test
    void sinIdiomaDelUsuarioLaFacturaSaleEnEspanol() {
        usuarioConIdioma("  ");

        controller.myInvoice(auth, orderId, null);

        verify(orderUseCase).getMyOrderDetail(userId, orderId, "es");
    }

    @Test
    void unUsuarioQueYaNoExisteNoImpideDescargarLaFactura() {
        when(userRepository.getById(userId)).thenReturn(null);

        controller.myInvoice(auth, orderId, null);

        verify(orderUseCase).getMyOrderDetail(userId, orderId, "es");
    }

    @Test
    void elPdfSeSirveEnLineaConElNumeroDePedidoComoNombre() {
        usuarioConIdioma("es");

        ResponseEntity<byte[]> resp = controller.myInvoice(auth, orderId, null);

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline; filename=\"factura-NX-100.pdf\"");
        assertThat(resp.getBody()).isNotEmpty();
    }

    @Test
    void laMonedaDeLaFacturaEsLaQueSeLiquidoEnElPago() {
        // El PDF tiene que coincidir con el cargo real: si se pagó en euros, la factura va en euros.
        usuarioConIdioma("es");
        when(paymentRepository.findSettlementCurrenciesByOrderId(orderId)).thenReturn(List.of("EUR"));

        controller.myInvoice(auth, orderId, null);

        verify(invoiceService).renderPdf(pedido, "es", "EUR");
    }

    @Test
    void unPagoEnCriptoNoSeFacturaEnUsdtSinoEnLaMonedaDelPedido() {
        // USDT no es una divisa de facturación: la factura debe ir en la moneda canónica del pedido.
        usuarioConIdioma("es");
        when(paymentRepository.findSettlementCurrenciesByOrderId(orderId)).thenReturn(List.of("USDT", "EUR"));

        controller.myInvoice(auth, orderId, null);

        verify(invoiceService).renderPdf(pedido, "es", "EUR");
    }

    @Test
    void sinMonedaDeLiquidacionNiMonedaDeAsientoSeFacturaEnDolares() {
        usuarioConIdioma("es");
        pedido.setCurrency(null);
        when(paymentRepository.findSettlementCurrenciesByOrderId(orderId)).thenReturn(List.of());

        controller.myInvoice(auth, orderId, null);

        verify(invoiceService).renderPdf(pedido, "es", "USD");
    }

    @Test
    void elAdminResuelveElPedidoConElIdiomaDefinitivoAntesDePintarlo() {
        // La primera lectura solo sirve para saber de quién es el pedido y en qué idioma habla.
        usuarioConIdioma("it");

        controller.adminInvoice(orderId, null);

        verify(orderUseCase).getAdminOrderDetail(orderId, null);
        verify(orderUseCase).getAdminOrderDetail(orderId, "it");
        verify(invoiceService).renderPdf(pedido, "it", "USD");
    }

    @Test
    void laFacturaDeUnPlanSeSirveConSuNumeroComoNombreDeFichero() throws StripeException {
        usuarioConIdioma("es");
        when(customerSubscriptionUseCase.renderInvoicePdf(userId, "IN-42", "es")).thenReturn("%PDF".getBytes());

        ResponseEntity<byte[]> resp = controller.myPlanInvoice(auth, "IN-42", null);

        assertThat(resp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .isEqualTo("inline; filename=\"factura-IN-42.pdf\"");
        assertThat(resp.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PDF);
    }

    @Test
    void elCorreoDePruebaUsaElMetodoDePagoRealDelPedido() {
        PaymentEntity pago = new PaymentEntity();
        pago.setMethod(PaymentMethod.PAYPAL);
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(pago));

        controller.sendTest(orderId, "qa@test", "en");

        verify(orderEmailService).paymentConfirmed(pedido, "qa@test", "en", "PAYPAL", "USD");
    }

    @Test
    void unPedidoPagadoConSaldoSeEnsenaConTarjetaComoMetodoRepresentativo() {
        // Sin pago externo registrado no hay método que mostrar; CARD es el marcador de la plantilla.
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp = controller.sendTest(orderId, "qa@test", null);

        verify(orderEmailService).paymentConfirmed(pedido, "qa@test", "es", "CARD", "USD");
        assertThat(resp.getBody()).containsEntry("sent", true).containsEntry("to", "qa@test").containsEntry("order",
                "NX-100");
    }

    @Test
    void unPagoSinMetodoRegistradoNoRompeElCorreoDePrueba() {
        PaymentEntity sinMetodo = new PaymentEntity();
        when(paymentRepository.findByOrderIdOrderByCreatedAtDesc(orderId)).thenReturn(List.of(sinMetodo));

        controller.sendTest(orderId, "qa@test", "es");

        verify(orderEmailService).paymentConfirmed(pedido, "qa@test", "es", "CARD", "USD");
    }
}
