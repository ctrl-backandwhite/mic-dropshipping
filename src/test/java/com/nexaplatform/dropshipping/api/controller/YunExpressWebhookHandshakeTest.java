package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * El saludo con el que YunExpress da por buena la dirección del webhook.
 *
 * <p>Registrar la URL en el console fallaba con {@code URL校验失败} y no había forma de saber por qué:
 * su documentación dice que la comprobación de dirección va SIN firma, y a partir de ahí solo cabía
 * adivinar. Se capturó la petición real (17-ago-2026) y resultó ser justo lo contrario:
 *
 * <pre>
 * POST /api/webhooks/yunexpress
 * X-Openapi-Request-Timestamp: 1786999735518
 * X-Openapi-Signature: f85ba2a1…            ← firmada, y válida con NUESTRA clave
 * {"encrypt":"Hv+U07un…"}                   ← que descifrado es {"ack":"f416227d…"}
 * </pre>
 *
 * <p>Es decir: <b>no hace falta abrir ninguna puerta sin firma</b>. La comprobación entra por el mismo
 * sitio que los avisos reales, con la misma firma y el mismo cifrado; lo único que faltaba era
 * reconocerla y devolver el {@code ack} en vez de tratarla como un evento de trazabilidad. La
 * verificación de integridad se queda intacta —{@code YunExpressWebhookIntegrityOrderTest} sigue
 * mandando— y un saludo sin firmar se rechaza como cualquier otra cosa sin firmar.
 */
class YunExpressWebhookHandshakeTest {

    /** Una clave cualquiera: lo que se prueba es el protocolo, no el secreto. */
    private static final String KEY = "0123456789abcdef0123456789abcdef";

    private final YunExpressEventCipher cipher = new YunExpressEventCipher();
    private final FulfillmentService fulfillment = mock(FulfillmentService.class);
    private final YunExpressWebhookController controller = new YunExpressWebhookController(cipher, fulfillment);

    YunExpressWebhookHandshakeTest() {
        ReflectionTestUtils.setField(cipher, "encryptKey", KEY);
    }

    @Test
    @DisplayName("al saludo firmado se le devuelve su ack, y no se procesa como trazabilidad")
    void alSaludoSeLeDevuelveElAck() throws Exception {
        String ack = "f416227dac0a42989e17a6d8b22c93d2";
        String body = sobre("{\"ack\":\"" + ack + "\"}");
        String ts = "1786999735518";

        ResponseEntity<String> res = controller.receive(ts, firma(ts, body), body);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).contains(ack);
        verify(fulfillment, never()).applyYunExpressPush(anyString());
    }

    @Test
    @DisplayName("un saludo SIN firma se rechaza igual que cualquier otra cosa sin firmar")
    void unSaludoSinFirmaSeRechaza() throws Exception {
        String body = sobre("{\"ack\":\"f416227dac0a42989e17a6d8b22c93d2\"}");

        ResponseEntity<String> res = controller.receive("1786999735518", null, body);

        assertThat(res.getStatusCode().value()).as("la comprobación de firma no se debilita").isEqualTo(401);
        verify(fulfillment, never()).applyYunExpressPush(anyString());
    }

    @Test
    @DisplayName("un aviso de trazabilidad normal se sigue procesando")
    void unAvisoNormalSeProcesa() throws Exception {
        String body = sobre("{\"data_code\":\"tisPushData\",\"data\":{\"waybill_number\":\"YT240TEST103\"}}");
        String ts = "1786999735518";

        ResponseEntity<String> res = controller.receive(ts, firma(ts, body), body);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        verify(fulfillment).applyYunExpressPush(body);
    }

    @Test
    @DisplayName("al aviso procesado se le contesta «success», que es lo que ellos esperan leer")
    void alAvisoSeLeContestaSuccess() throws Exception {
        // Su documentación: «After receiving the data, the docking party returns the string "success".
        // OpenApi receives this message and considers the push successful.» Contestar otra cosa deja el
        // aviso por fallido y lo reintentan.
        String body = sobre("{\"data_code\":\"tisPushData\",\"data\":{}}");
        String ts = "1786999735518";

        ResponseEntity<String> res = controller.receive(ts, firma(ts, body), body);

        assertThat(res.getBody()).isEqualTo("success");
    }

    /* ---------- Utilidades: se construye el sobre igual que lo construye YunExpress ---------- */

    /** {@code {"encrypt": base64(IV + AES-256-CBC(contenido))}}, con clave SHA-256 de la encrypt key. */
    private static String sobre(String claro) throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(KEY.getBytes(StandardCharsets.UTF_8));
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] enc = c.doFinal(claro.getBytes(StandardCharsets.UTF_8));
        byte[] todo = new byte[iv.length + enc.length];
        System.arraycopy(iv, 0, todo, 0, iv.length);
        System.arraycopy(enc, 0, todo, iv.length, enc.length);
        return "{\"encrypt\":\"" + Base64.getEncoder().encodeToString(todo) + "\"}";
    }

    /** {@code hex(SHA-256(timestamp + encryptKey + cuerpo))}, la firma que ellos envían. */
    private static String firma(String timestamp, String body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(
                md.digest((timestamp + KEY + body).getBytes(StandardCharsets.UTF_8)));
    }
}
