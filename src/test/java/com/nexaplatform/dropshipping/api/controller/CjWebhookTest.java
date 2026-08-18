package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.controller.CjWebhookController.AvisoDeCj;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjAuthService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj.CjWebhookSigner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Los avisos que CJ empuja a nuestro webhook: qué se acepta, qué se rechaza y qué no se procesa dos
 * veces.
 *
 * <p>El contrato de CJ (documentación consultada el 18-ago-2026) pone tres condiciones que no son
 * negociables, y cada una tiene su precio si se incumple:
 *
 * <ul>
 *   <li><b>La firma va sobre el cuerpo BRUTO</b>: {@code sign = Base64(HmacSHA256(openId, cuerpo))}.
 *       Deserializar y volver a serializar cambia espacios y orden de campos, y entonces la firma no
 *       cuadra <i>nunca</i>. Aquí se fija con un cuerpo con saltos de línea y con acentos y caracteres
 *       chinos, y con la comprobación explícita de que el mismo JSON reordenado ya no valida.</li>
 *   <li><b>200 en menos de tres segundos</b>: CJ reintenta tres veces y <b>desactiva el webhook</b> si
 *       el éxito baja del 80 % durante dos horas seguidas. Por eso el trabajo de verdad no se hace
 *       dentro: se entrega el aviso y se contesta.</li>
 *   <li><b>El {@code messageId} se repite en los reintentos</b>: si se procesa dos veces, el pedido
 *       recibe el mismo hito duplicado — el fallo que ya se pagó una vez en el seguimiento.</li>
 * </ul>
 *
 * <p>La firma se calcula aquí con una implementación propia de HmacSHA256 y no con la de producción:
 * si las dos estuvieran mal de la misma manera, la prueba pasaría igual y no diría nada.
 */
class CjWebhookTest {

    /** El openId real de esta cuenta. Llega como número en el JSON de autenticación y se usa como cadena. */
    private static final String OPEN_ID = "33689";

    private final CjAuthService auth = mock(CjAuthService.class);
    private final ApplicationEventPublisher eventos = mock(ApplicationEventPublisher.class);
    private final CjWebhookController controlador = new CjWebhookController(auth, eventos);

    CjWebhookTest() {
        when(auth.openId()).thenReturn(OPEN_ID);
    }

    @Test
    @DisplayName("un aviso bien firmado se acepta con 200 y se entrega para procesarlo fuera de línea")
    void avisoBienFirmadoSeAceptaYSeEntrega() {
        String cuerpo = aviso("msg-1");

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(cuerpo), bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        AvisoDeCj entregado = capturarEntregado();
        assertThat(entregado.messageId()).isEqualTo("msg-1");
        assertThat(entregado.tipo()).isEqualTo(CjWebhookController.TipoDeAviso.LOGISTICS);
        assertThat(entregado.accion()).isEqualTo("UPDATE");
        assertThat(entregado.cuerpo()).as("el consumidor recibe el cuerpo tal y como llegó").isEqualTo(cuerpo);
    }

    @Test
    @DisplayName("una firma que no cuadra se rechaza con 401 y no se guarda nada")
    void firmaQueNoCuadraSeRechaza() {
        String cuerpo = aviso("msg-2");

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(aviso("otro")), bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(401);
        verify(eventos, never()).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("un aviso sin la cabecera sign se rechaza con 401")
    void avisoSinFirmaSeRechaza() {
        String cuerpo = aviso("msg-3");

        ResponseEntity<String> respuesta = controlador.recibir(null, bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(401);
        verify(eventos, never()).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("sin openId no se puede comprobar nada, así que se rechaza en vez de dar por bueno")
    void sinOpenIdSeRechaza() {
        when(auth.openId()).thenReturn(null);
        String cuerpo = aviso("msg-4");

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(cuerpo), bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(401);
        verifyNoInteractions(eventos);
    }

    @Test
    @DisplayName("el mismo messageId repetido se procesa una sola vez, y las dos veces responde 200")
    void messageIdRepetidoSeProcesaUnaSolaVez() {
        String cuerpo = aviso("msg-repetido");
        String firma = firmaDe(cuerpo);

        ResponseEntity<String> primera = controlador.recibir(firma, bytes(cuerpo));
        ResponseEntity<String> segunda = controlador.recibir(firma, bytes(cuerpo));

        assertThat(primera.getStatusCode().value()).isEqualTo(200);
        assertThat(segunda.getStatusCode().value())
                .as("un error haría que CJ reintentara eternamente el mismo aviso").isEqualTo(200);
        verify(eventos, times(1)).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("dos avisos distintos se entregan los dos")
    void dosAvisosDistintosSeEntreganLosDos() {
        controlador.recibir(firmaDe(aviso("msg-a")), bytes(aviso("msg-a")));
        controlador.recibir(firmaDe(aviso("msg-b")), bytes(aviso("msg-b")));

        verify(eventos, times(2)).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("un tipo de aviso que no conocemos se acepta con 200 pero no se procesa")
    void tipoDesconocidoSeAceptaSinEfecto() {
        String cuerpo = "{\"messageId\":\"msg-5\",\"type\":\"inventarioLunar\",\"messageType\":\"INSERT\","
                + "\"params\":{}}";

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(cuerpo), bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value())
                .as("que CJ añada tipos nuevos no es un fallo nuestro").isEqualTo(200);
        verify(eventos, never()).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("el tipo se reconoce escrito como lo escribe CJ: privateOrder y PRIVATE_ORDER")
    void elTipoSeReconoceEnLasDosGrafias() {
        String enCamello = "{\"messageId\":\"msg-6\",\"type\":\"privateOrder\",\"messageType\":\"UPDATE\"}";
        String conGuion = "{\"messageId\":\"msg-7\",\"type\":\"PRIVATE_ORDER\",\"messageType\":\"UPDATE\"}";

        controlador.recibir(firmaDe(enCamello), bytes(enCamello));
        controlador.recibir(firmaDe(conGuion), bytes(conGuion));

        ArgumentCaptor<AvisoDeCj> capturados = ArgumentCaptor.forClass(AvisoDeCj.class);
        verify(eventos, times(2)).publishEvent(capturados.capture());
        assertThat(capturados.getAllValues()).allMatch(
                aviso -> aviso.tipo() == CjWebhookController.TipoDeAviso.PRIVATE_ORDER);
    }

    @Test
    @DisplayName("un cuerpo con saltos de línea y caracteres no ASCII se firma sobre los bytes originales")
    void cuerpoConSaltosDeLineaYUnicodeSeFirmaSobreLosBytesOriginales() {
        String cuerpo = """
                {
                  "messageId": "msg-8",
                  "type": "logistics",
                  "messageType": "UPDATE",
                  "params": {"remark": "Envío a A Coruña — 包裹已签收"}
                }""";

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(cuerpo), bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        assertThat(capturarEntregado().cuerpo()).isEqualTo(cuerpo);
    }

    @Test
    @DisplayName("el mismo JSON reserializado ya no cuadra con la firma: por eso se lee el cuerpo bruto")
    void elMismoJsonReserializadoNoCuadra() {
        String comoLlega = """
                {
                  "messageId": "msg-9",
                  "type": "logistics",
                  "messageType": "UPDATE"
                }""";
        String reserializado = "{\"type\":\"logistics\",\"messageType\":\"UPDATE\",\"messageId\":\"msg-9\"}";

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(comoLlega), bytes(reserializado));

        assertThat(respuesta.getStatusCode().value())
                .as("si el controlador reserializara el cuerpo, ningún aviso pasaría jamás").isEqualTo(401);
        assertThat(CjWebhookSigner.verificar(OPEN_ID, bytes(reserializado), firmaDe(comoLlega))).isFalse();
        assertThat(CjWebhookSigner.verificar(OPEN_ID, bytes(comoLlega), firmaDe(comoLlega))).isTrue();
    }

    @Test
    @DisplayName("un cuerpo firmado pero ilegible se acepta con 200: reintentarlo no lo va a arreglar")
    void cuerpoIlegibleSeAceptaSinEfecto() {
        String cuerpo = "esto no es JSON";

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(cuerpo), bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        verify(eventos, never()).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("un cuerpo vacío no revienta: se acepta con 200 y no se procesa")
    void cuerpoVacioSeAceptaSinEfecto() {
        String cuerpo = "";

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(cuerpo), bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        verify(eventos, never()).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("un aviso sin messageId se entrega igualmente: perder una entrega es peor que repetirla")
    void avisoSinMessageIdSeEntregaIgualmente() {
        String cuerpo = "{\"type\":\"logistics\",\"messageType\":\"UPDATE\",\"params\":{}}";

        ResponseEntity<String> respuesta = controlador.recibir(firmaDe(cuerpo), bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
        verify(eventos, times(1)).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("si la entrega falla se responde 500 y el reintento de CJ vuelve a intentarlo")
    void siLaEntregaFallaElReintentoVuelveAIntentarlo() {
        doThrow(new IllegalStateException("cola caída")).doNothing()
                .when(eventos).publishEvent(any(AvisoDeCj.class));
        String cuerpo = aviso("msg-10");
        String firma = firmaDe(cuerpo);

        ResponseEntity<String> primera = controlador.recibir(firma, bytes(cuerpo));
        ResponseEntity<String> segunda = controlador.recibir(firma, bytes(cuerpo));

        assertThat(primera.getStatusCode().value()).isEqualTo(500);
        assertThat(segunda.getStatusCode().value())
                .as("el aviso no llegó a procesarse, así que el reintento no es un duplicado").isEqualTo(200);
        verify(eventos, times(2)).publishEvent(any(AvisoDeCj.class));
    }

    @Test
    @DisplayName("la firma es Base64 de HmacSHA256 con el openId como clave, sobre los bytes del cuerpo")
    void laFirmaEsBase64DeHmacSha256ConElOpenId() {
        String cuerpo = aviso("msg-11");

        assertThat(CjWebhookSigner.firmar(OPEN_ID, bytes(cuerpo))).isEqualTo(firmaDe(cuerpo));
        assertThat(CjWebhookSigner.firmar(OPEN_ID, cuerpo)).isEqualTo(firmaDe(cuerpo));
    }

    @Test
    @DisplayName("sin openId, sin firma o sin cuerpo, verificar dice que no: nunca da por buena una duda")
    void verificarEsCerradoPorDefecto() {
        String cuerpo = aviso("msg-12");

        assertThat(CjWebhookSigner.verificar(null, bytes(cuerpo), firmaDe(cuerpo))).isFalse();
        assertThat(CjWebhookSigner.verificar("  ", bytes(cuerpo), firmaDe(cuerpo))).isFalse();
        assertThat(CjWebhookSigner.verificar(OPEN_ID, bytes(cuerpo), null)).isFalse();
        assertThat(CjWebhookSigner.verificar(OPEN_ID, bytes(cuerpo), "  ")).isFalse();
        assertThat(CjWebhookSigner.verificar(OPEN_ID, null, firmaDe(cuerpo))).isFalse();
        assertThat(CjWebhookSigner.verificar("otroOpenId", bytes(cuerpo), firmaDe(cuerpo))).isFalse();
    }

    @Test
    @DisplayName("la cabecera sign se acepta aunque venga con espacios alrededor")
    void laCabeceraSignSeAceptaConEspacios() {
        String cuerpo = aviso("msg-13");

        ResponseEntity<String> respuesta = controlador.recibir("  " + firmaDe(cuerpo) + " ", bytes(cuerpo));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(200);
    }

    private AvisoDeCj capturarEntregado() {
        ArgumentCaptor<AvisoDeCj> capturado = ArgumentCaptor.forClass(AvisoDeCj.class);
        verify(eventos).publishEvent(capturado.capture());
        return capturado.getValue();
    }

    private static String aviso(String messageId) {
        return "{\"messageId\":\"" + messageId + "\",\"type\":\"logistics\",\"messageType\":\"UPDATE\","
                + "\"params\":{\"orderId\":\"CJ-1\",\"trackNumber\":\"YT2612\"}}";
    }

    private static byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    /** HmacSHA256 hecho aquí a mano, para no comprobar la implementación contra sí misma. */
    private static String firmaDe(String cuerpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(OPEN_ID.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(bytes(cuerpo)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
