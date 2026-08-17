package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.HexFormat;

/**
 * Verificación y descifrado de los eventos que YunExpress empuja a nuestro webhook (事件管理).
 *
 * <p>El push llega firmado y con el cuerpo cifrado, ambos derivados del <b>Encrypt Key</b> que se
 * configura en el console (开发配置 → 事件管理 → 加密策略):
 * <ul>
 *   <li><b>Firma</b>: {@code X-Openapi-Signature = hex(SHA-256(timestamp + encryptKey + cuerpoCrudo))},
 *       donde {@code timestamp} es la cabecera {@code X-Openapi-Request-Timestamp}. Se compara en tiempo
 *       constante para no filtrar información por el tiempo de respuesta.</li>
 *   <li><b>Cifrado</b>: AES-256-CBC con {@code clave = SHA-256(encryptKey)} y el IV en los 16 primeros
 *       bytes del contenido una vez descodificado el Base64.</li>
 * </ul>
 *
 * <p>Sin {@code encrypt-key} configurado el componente queda inactivo y el webhook rechaza los pushes:
 * es preferible perder eventos (el sondeo periódico los recupera) a procesar uno no verificado.
 *
 * <h2>Por qué CIPHER_INTEGRITY (Find Security Bugs) NO aplica aquí</h2>
 *
 * <p>El analizador marca {@code AES/CBC} sin MAC como «cifrado sin comprobación de integridad» y sugiere
 * GCM. Aquí no procede, y no por comodidad sino por estas cuatro razones comprobables:
 *
 * <ol>
 *   <li><b>El modo lo impone el proveedor.</b> El sobre lo cifra YunExpress con AES-256-CBC según su
 *       contrato de 事件管理; no somos el emisor, así que no podemos migrar a GCM unilateralmente. Cambiarlo
 *       sería, sencillamente, dejar de poder leer sus eventos.</li>
 *   <li><b>La integridad ya está cubierta, y ANTES de descifrar.</b> {@link #verify} valida
 *       {@code SHA-256(timestamp + encryptKey + cuerpoCrudo)} sobre el cuerpo <i>crudo</i>, que es el que
 *       transporta el campo {@code encrypt} con el criptograma. Es decir: la firma <b>cubre el texto
 *       cifrado completo</b>, con una clave secreta compartida, así que cualquier bit volteado en el
 *       criptograma rompe la firma. {@code YunExpressWebhookController} corta con 401 antes de invocar
 *       {@code FulfillmentService.applyYunExpressPush}, que es el ÚNICO camino que llega a
 *       {@link #decrypt}. El orden verificar→descifrar es justo lo que exige la construcción
 *       encrypt-then-MAC, y está fijado por test (ver {@code YunExpressWebhookIntegrityOrderTest}).</li>
 *   <li><b>No hay oráculo de padding.</b> El descifrado usa {@code AES/CBC/NoPadding}: el JCE no valida
 *       relleno, luego no puede lanzar {@code BadPaddingException} ni ninguna otra excepción que distinga
 *       «relleno correcto» de «relleno incorrecto». El relleno se recorta a mano en {@link #stripPadding}.
 *       Sin señal diferenciada no hay oráculo que interrogar, ni siquiera hipotéticamente.</li>
 *   <li><b>No hay canal de respuesta que explotar.</b> Todo fallo de firma devuelve el mismo 401 con el
 *       mismo cuerpo, y la comparación es en tiempo constante ({@link MessageDigest#isEqual}), así que no
 *       se filtra información ni por el contenido ni por el tiempo.</li>
 * </ol>
 *
 * <p>Conclusión: FALSO POSITIVO justificado. Si alguien reordena el controller para descifrar antes de
 * verificar, deja de serlo — por eso ese orden está protegido por test y no solo por este comentario.
 */
@Slf4j
@Component
public class YunExpressEventCipher {

    /** Solo se usa para mirar dentro del sobre; el contenido real lo interpreta el servicio. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int IV_LENGTH = 16;

    @Value("${nexadrop.yunexpress.encrypt-key:}")
    private String encryptKey;

    public boolean isConfigured() {
        return encryptKey != null && !encryptKey.isBlank();
    }

    /** ¿La firma del push cuadra con el cuerpo recibido? Fail-closed si no hay clave configurada. */
    public boolean verify(String timestamp, String rawBody, String signature) {
        if (!isConfigured() || timestamp == null || rawBody == null || signature == null) {
            return false;
        }
        String expected = signature(timestamp, rawBody, encryptKey);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Firma esperada de un push: {@code hex(SHA-256(timestamp + encryptKey + cuerpo))} en minúsculas. */
    static String signature(String timestamp, String rawBody, String key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((timestamp + key + rawBody).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("YunExpress: SHA-256 no disponible", e);
        }
    }

    /**
     * Descifra el contenido del evento (Base64 de IV + criptograma AES-256-CBC).
     *
     * <p><b>PRECONDICIÓN</b>: solo se debe llamar sobre un cuerpo cuya firma ya haya pasado por
     * {@link #verify}. Esa firma es la que aporta la integridad que CBC no trae (ver el javadoc de la
     * clase); descifrar antes de verificar convertiría el aviso CIPHER_INTEGRITY en un fallo real.
     */
    public String decrypt(String base64Content) {
        return decrypt(base64Content, encryptKey);
    }

    /**
     * El {@code ack} del saludo con el que YunExpress comprueba la dirección del webhook, o
     * {@code null} si el sobre no es un saludo sino un aviso de trazabilidad.
     *
     * <p>Al guardar la URL en el console mandan un POST <b>firmado y cifrado igual que los avisos
     * reales</b> —capturado el 17-ago-2026— cuyo contenido descifrado es {@code {"ack":"<valor>"}}. Hay
     * que devolver ese valor; con cualquier otra respuesta el console contesta {@code URL校验失败} y la
     * dirección no se puede registrar. (Su documentación dice que esa comprobación va sin firma; no es
     * cierto, y menos mal: así no hace falta abrir ninguna puerta.)
     *
     * <p>Vive aquí, junto al descifrado, por la misma <b>PRECONDICIÓN</b> que {@link #decrypt}: se llama
     * solo sobre un cuerpo cuya firma ya pasó por {@link #verify}. Reconocer el saludo obliga a abrir el
     * sobre, y abrir sobres es de esta clase, no del controlador.
     */
    public String ackOf(String rawBody) {
        try {
            JsonNode sobre = JSON.readTree(rawBody);
            String contenido = sobre.hasNonNull("encrypt")
                    ? decrypt(sobre.get("encrypt").asText())
                    : rawBody;
            String ack = JSON.readTree(contenido).path("ack").asText("");
            return ack.isBlank() ? null : ack;
        } catch (JsonProcessingException | RuntimeException e) {
            // Lo que no se puede leer como sobre no es un saludo: que siga su camino.
            return null;
        }
    }

    static String decrypt(String base64Content, String key) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Content);
            if (decoded.length <= IV_LENGTH) {
                throw new IllegalArgumentException("contenido cifrado demasiado corto");
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            SecretKeySpec secretKey = new SecretKeySpec(digest.digest(key.getBytes(StandardCharsets.UTF_8)), "AES");
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);
            byte[] data = new byte[decoded.length - IV_LENGTH];
            System.arraycopy(decoded, IV_LENGTH, data, 0, data.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(iv));
            return new String(stripPadding(cipher.doFinal(data)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("YunExpress: no se pudo descifrar el evento del webhook", e);
        }
    }

    /**
     * Quita el relleno del bloque final. Se descifra con {@code NoPadding} —como en el ejemplo oficial—
     * porque el emisor no siempre rellena en PKCS#7 estricto, así que se recortan a mano los bytes de
     * relleno del final (valor {@code <= 16}, que nunca es texto imprimible).
     */
    private static byte[] stripPadding(byte[] plain) {
        int end = plain.length - 1;
        while (end >= 0 && plain[end] <= IV_LENGTH) {
            end--;
        }
        if (end == plain.length - 1) {
            return plain;
        }
        byte[] trimmed = new byte[end + 1];
        System.arraycopy(plain, 0, trimmed, 0, end + 1);
        return trimmed;
    }
}
