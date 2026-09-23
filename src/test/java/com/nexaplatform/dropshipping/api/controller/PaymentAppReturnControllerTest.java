package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * La vuelta del Checkout hospedado a la APLICACIÓN.
 *
 * <p>Stripe solo admite direcciones http(s) como página de retorno, y el teléfono solo sabe devolver
 * el foco a la app por su esquema propio. Sin este puente, quien recargaba desde el móvil pagaba en
 * Stripe y se quedaba en una página en blanco: el cobro se hacía y la app no se enteraba.
 */
class PaymentAppReturnControllerTest {

    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final PaymentAppReturnController controller = new PaymentAppReturnController(payments);
    private final UUID paymentId = UUID.fromString("11111111-2222-3333-4444-555555555555");

    PaymentAppReturnControllerTest() {
        ReflectionTestUtils.setField(controller, "appPaymentReturnUrl", "nx036://pago");
        // Por omisión, una recarga del monedero: es el pago sin pedido detrás.
        when(payments.findById(any())).thenReturn(Optional.of(new PaymentEntity()));
    }

    @Test
    @DisplayName("el pago aprobado devuelve a la app por su esquema, con el identificador del pago")
    void elPagoAprobadoDevuelveALaApp() {
        ResponseEntity<Void> respuesta = controller.appReturn(paymentId, "ok", "cs_test_123");

        assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(respuesta.getHeaders().getLocation())
                .hasToString("nx036://pago/retorno?paymentId=" + paymentId + "&kind=recharge&sessionId=cs_test_123");
    }

    @Test
    @DisplayName("salir sin pagar devuelve a la ruta de cancelado, que la app distingue")
    void salirSinPagarDevuelveACancelado() {
        ResponseEntity<Void> respuesta = controller.appReturn(paymentId, "cancel", null);

        assertThat(respuesta.getHeaders().getLocation())
                .hasToString("nx036://pago/cancelado?paymentId=" + paymentId + "&kind=recharge");
    }

    /**
     * Pagar un PEDIDO se cierra por otro sitio que recargar el monedero, y al volver del navegador la
     * app ya no tiene contexto: el enlace tiene que decirle qué se estaba pagando.
     */
    @Test
    @DisplayName("el pago de un pedido vuelve diciendo que es un pedido, con su identificador")
    void elPagoDeUnPedidoVuelveIdentificado() {
        UUID orderId = UUID.fromString("99999999-8888-7777-6666-555555555555");
        PaymentEntity pago = new PaymentEntity();
        pago.setOrderId(orderId);
        when(payments.findById(paymentId)).thenReturn(Optional.of(pago));

        ResponseEntity<Void> respuesta = controller.appReturn(paymentId, "ok", "cs_test_9");

        assertThat(respuesta.getHeaders().getLocation()).hasToString("nx036://pago/retorno?paymentId=" + paymentId
                + "&kind=order&orderId=" + orderId + "&sessionId=cs_test_9");
    }

    /** Un estado desconocido se trata como cancelado: nunca se anuncia un cobro que no consta. */
    @Test
    @DisplayName("un estado que no reconocemos se trata como cancelado")
    void unEstadoDesconocidoSeTrataComoCancelado() {
        ResponseEntity<Void> respuesta = controller.appReturn(paymentId, "vete-a-saber", null);

        assertThat(respuesta.getHeaders().getLocation())
                .hasToString("nx036://pago/cancelado?paymentId=" + paymentId + "&kind=recharge");
    }
}
