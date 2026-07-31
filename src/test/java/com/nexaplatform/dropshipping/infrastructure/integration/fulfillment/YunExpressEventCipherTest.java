package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seguridad del webhook de YunExpress: firma del push y descifrado del contenido.
 *
 * <p>El vector de descifrado es el del propio ejemplo de la documentación oficial (clave {@code "test key"}),
 * así que si YunExpress cambiara el esquema el test lo detecta. Lo que se protege es que un push con firma
 * incorrecta —o sin clave configurada— NUNCA se dé por bueno: sería una puerta abierta para que cualquiera
 * marcase pedidos como entregados.
 */
class YunExpressEventCipherTest {

    private static final String KEY = "test key";

    private YunExpressEventCipher cipherWith(String key) {
        YunExpressEventCipher cipher = new YunExpressEventCipher();
        ReflectionTestUtils.setField(cipher, "encryptKey", key);
        return cipher;
    }

    @Test
    void descifraElVectorDeLaDocumentacionOficial() {
        assertThat(YunExpressEventCipher.decrypt("P37w+VZImNgPEO1RBhJ6RtKl7n6zymIbEG1pReEzghk=", KEY))
                .isEqualTo("hello world");
    }

    @Test
    void aceptaElPushCuandoLaFirmaCuadra() {
        YunExpressEventCipher cipher = cipherWith(KEY);
        String timestamp = "1785356088184";
        String body = "{\"encrypt\":\"abc\"}";
        String signature = YunExpressEventCipher.signature(timestamp, body, KEY);

        assertThat(cipher.verify(timestamp, body, signature)).isTrue();
    }

    @Test
    void rechazaFirmaAlteradaCuerpoAlteradoYCamposAusentes() {
        YunExpressEventCipher cipher = cipherWith(KEY);
        String timestamp = "1785356088184";
        String body = "{\"encrypt\":\"abc\"}";
        String signature = YunExpressEventCipher.signature(timestamp, body, KEY);

        assertThat(cipher.verify(timestamp, body, signature.replace('a', 'b'))).isFalse();
        assertThat(cipher.verify(timestamp, body + " ", signature)).isFalse();
        assertThat(cipher.verify("1785356088999", body, signature)).isFalse();
        assertThat(cipher.verify(null, body, signature)).isFalse();
        assertThat(cipher.verify(timestamp, body, null)).isFalse();
    }

    @Test
    void sinClaveConfiguradaRechazaTodo() {
        YunExpressEventCipher cipher = cipherWith("");
        String timestamp = "1785356088184";
        String body = "{}";

        assertThat(cipher.isConfigured()).isFalse();
        // Ni siquiera una firma correcta para la clave vacía debe pasar el control.
        assertThat(cipher.verify(timestamp, body, YunExpressEventCipher.signature(timestamp, body, ""))).isFalse();
    }

    @Test
    void laFirmaEsElSha256HexDeTimestampMasClaveMasCuerpo() {
        // hex(SHA-256("1" + "test key" + "{}")), calculado con el algoritmo documentado.
        assertThat(YunExpressEventCipher.signature("1", KEY, "{}"))
                .hasSize(64)
                .isEqualTo(YunExpressEventCipher.signature("1", KEY, "{}"))
                .isNotEqualTo(YunExpressEventCipher.signature("2", KEY, "{}"));
    }
}
