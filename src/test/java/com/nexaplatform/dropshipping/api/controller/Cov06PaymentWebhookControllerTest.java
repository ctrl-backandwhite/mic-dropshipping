package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.usecase.PaymentUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Seguridad de transporte de los webhooks de cobro. Aquí se decide si un mensaje que dice "este pedido
 * está pagado" viene de verdad de la pasarela: si estas reglas fallan, cualquiera puede marcar pedidos
 * como pagados sin pagar.
 */
@ExtendWith(MockitoExtension.class)
class Cov06PaymentWebhookControllerTest {

    private static final String SECRETO = "whsec_secreto_de_pruebas";

    @Mock
    PaymentUseCase paymentUseCase;
    @Mock
    OpsAlertService opsAlertService;

    @InjectMocks
    PaymentWebhookController controller;

    @BeforeEach
    void configurarSecretos() {
        // Los secretos llegan por @Value; en un test unitario hay que ponerlos a mano.
        ReflectionTestUtils.setField(controller, "stripeWebhookSecret", SECRETO);
        ReflectionTestUtils.setField(controller, "paypalWebhookSecret", SECRETO);
        ReflectionTestUtils.setField(controller, "coinbaseWebhookSecret", SECRETO);
    }

    /* ------------------------------ Stripe ------------------------------ */

    /**
     * Sin secreto configurado el evento no se puede verificar, pero contestar 2xx sería peor que el
     * fallo: Stripe lo daría por entregado y dejaría de reintentar, y esos cobros con tarjeta se
     * quedarían sin confirmar para siempre. Con 5xx los reintenta durante días y además se avisa al
     * responsable.
     */
    @Test
    void stripeSinSecretoDevuelve503YAvisaAOperaciones() {
        ReflectionTestUtils.setField(controller, "stripeWebhookSecret", "");

        ResponseEntity<String> resp = controller.stripe("{\"type\":\"x\"}", "firma");

        assertThat(resp.getStatusCode().value()).isEqualTo(503);
        verify(opsAlertService).paymentFailed(eq("stripe"), anyString(), any(), anyString());
        verifyNoInteractions(paymentUseCase);
    }

    @Test
    void stripeConFirmaInvalidaNoProcesaElEvento() {
        ResponseEntity<String> resp = controller.stripe("{\"type\":\"checkout.session.completed\"}", "t=1,v1=deadbeef");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        assertThat(resp.getBody()).isEqualTo("bad signature");
        verifyNoInteractions(paymentUseCase);
    }

    @Test
    void stripeConFirmaValidaDelegaElEventoEnElCasoDeUso() {
        String payload = "{\"type\":\"checkout.session.completed\"}";
        when(paymentUseCase.handleStripeEvent("checkout.session.completed", payload)).thenReturn("ok");

        ResponseEntity<String> resp = controller.stripe(payload, cabeceraStripeFirmada(payload));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo("ok");
    }

    /* ------------------------------ HMAC (PayPal / Coinbase) ------------------------------ */

    /**
     * Fail-closed: sin secreto configurado NO se acepta ningún webhook. La versión anterior devolvía
     * "válido" cuando no había secreto, así que cualquiera podía falsificar un pago.
     */
    @Test
    void paypalSinSecretoRechazaAunqueLaFirmaVengaVacia() {
        ReflectionTestUtils.setField(controller, "paypalWebhookSecret", "");

        ResponseEntity<String> resp = controller.paypal("{}", hmacHex("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(paymentUseCase);
    }

    @Test
    void paypalSinFirmaRechaza() {
        ResponseEntity<String> resp = controller.paypal("{}", null);

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(paymentUseCase);
    }

    /** Una firma que ni siquiera es hexadecimal es un webhook a rechazar, no un 500 del servidor. */
    @Test
    void paypalConFirmaNoHexadecimalRechazaSinReventar() {
        ResponseEntity<String> resp = controller.paypal("{}", "no-es-hexadecimal");

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(paymentUseCase);
    }

    /** La firma correcta de OTRO contenido no vale para este: si no, se podrían replicar mensajes. */
    @Test
    void paypalConFirmaDeOtroContenidoRechaza() {
        ResponseEntity<String> resp = controller.paypal("{\"event\":\"PAYMENT.CAPTURE.COMPLETED\"}", hmacHex("{}"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(paymentUseCase);
    }

    @Test
    void paypalConFirmaValidaDelegaEnElCasoDeUso() {
        String payload = "{\"event\":\"PAYMENT.CAPTURE.COMPLETED\"}";
        when(paymentUseCase.handlePayPalEvent(payload)).thenReturn("ok");

        ResponseEntity<String> resp = controller.paypal(payload, hmacHex(payload));

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).isEqualTo("ok");
    }

    /** La firma se compara en bytes: mayúsculas/minúsculas y espacios sobrantes no invalidan un envío legítimo. */
    @Test
    void laFirmaHexadecimalSeAceptaEnMayusculasYConEspacios() {
        String payload = "{\"event\":\"charge:confirmed\"}";
        when(paymentUseCase.handleCoinbaseEvent(payload)).thenReturn("ok");

        ResponseEntity<String> resp = controller.coinbase(payload,
                "  " + hmacHex(payload).toUpperCase(Locale.ROOT) + "  ");

        assertThat(resp.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void coinbaseConFirmaInvalidaRechaza() {
        ResponseEntity<String> resp = controller.coinbase("{}", hmacHex("otra cosa"));

        assertThat(resp.getStatusCode().value()).isEqualTo(400);
        verifyNoInteractions(paymentUseCase);
    }

    /* ------------------------------ helpers ------------------------------ */

    /** HMAC-SHA256 hexadecimal del contenido con el secreto configurado (lo que manda la pasarela). */
    private static String hmacHex(String payload) {
        return HexFormat.of().formatHex(hmac(payload.getBytes(StandardCharsets.UTF_8)));
    }

    /** Cabecera {@code Stripe-Signature} bien formada: {@code t=<epoch>,v1=<hmac(t.payload)>}. */
    private static String cabeceraStripeFirmada(String payload) {
        long ts = Instant.now().getEpochSecond();
        String firmado = ts + "." + payload;
        return "t=" + ts + ",v1=" + HexFormat.of().formatHex(hmac(firmado.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRETO.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
