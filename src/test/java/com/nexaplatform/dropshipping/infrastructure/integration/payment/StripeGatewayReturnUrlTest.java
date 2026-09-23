package com.nexaplatform.dropshipping.infrastructure.integration.payment;

import com.nexaplatform.dropshipping.domain.enums.PaymentClientTarget;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A dónde vuelve el Checkout hospedado de Stripe.
 *
 * <p>Cuando el cobro sale del teléfono tiene que volver al puente de este backend, que redirige al
 * enlace profundo de la app. Volvía siempre al escaparate: en el móvil eso deja al navegador en una
 * dirección que el teléfono no sabe abrir, con el cobro hecho y la app sin enterarse.
 */
class StripeGatewayReturnUrlTest {

    private final StripeGateway gateway = new StripeGateway();
    private final UUID paymentId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    StripeGatewayReturnUrlTest() {
        ReflectionTestUtils.setField(gateway, "appBridgeBaseUrl", "http://10.0.2.2:18082");
    }

    private PaymentEntity pagoDe(PaymentClientTarget origen) {
        PaymentEntity p = new PaymentEntity();
        p.setId(paymentId);
        p.setClientTarget(origen);
        return p;
    }

    @Test
    @DisplayName("un cobro abierto desde la app vuelve al puente, que lleva al enlace profundo")
    void elCobroDeLaAppVuelveAlPuente() {
        assertThat(gateway.vueltaDeLaApp(pagoDe(PaymentClientTarget.MOBILE), true))
                .isEqualTo("http://10.0.2.2:18082/api/payments/app-return?paymentId=" + paymentId
                        + "&status=ok&session_id={CHECKOUT_SESSION_ID}");
    }

    @Test
    @DisplayName("salir sin pagar desde la app vuelve al puente marcado como cancelado")
    void salirSinPagarDesdeLaAppVuelveMarcado() {
        assertThat(gateway.vueltaDeLaApp(pagoDe(PaymentClientTarget.MOBILE), false))
                .isEqualTo("http://10.0.2.2:18082/api/payments/app-return?paymentId=" + paymentId + "&status=cancel");
    }

    /** Lo que viene de la web sigue igual: la página de retorno del escaparate. */
    @Test
    @DisplayName("un cobro de la web no usa el puente de la aplicación")
    void elCobroDeLaWebNoUsaElPuente() {
        assertThat(gateway.vueltaDeLaApp(pagoDe(PaymentClientTarget.WEB), true)).isNull();
    }
}
