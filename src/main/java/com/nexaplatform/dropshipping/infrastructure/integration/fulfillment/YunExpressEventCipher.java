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
 */
@Slf4j
@Component
public class YunExpressEventCipher {

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

    /** Descifra el contenido del evento (Base64 de IV + criptograma AES-256-CBC). */
    public String decrypt(String base64Content) {
        return decrypt(base64Content, encryptKey);
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
