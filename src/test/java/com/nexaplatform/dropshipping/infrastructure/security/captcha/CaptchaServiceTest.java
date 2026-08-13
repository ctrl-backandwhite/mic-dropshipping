package com.nexaplatform.dropshipping.infrastructure.security.captcha;

import com.nexaplatform.dropshipping.api.dto.out.CaptchaChallengeDtoOut;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifica el protocolo ALTCHA del {@link CaptchaService}: un reto legítimo se resuelve y valida una
 * sola vez; cualquier manipulación (número, firma), la reutilización, la caducidad y el modo desactivado
 * se comportan como toca.
 */
class CaptchaServiceTest {

    private CaptchaService service(long maxNumber, long expirySeconds, boolean enabled) {
        CaptchaService s = new CaptchaService();
        ReflectionTestUtils.setField(s, "configuredKey", "clave-fija-de-test-para-hmac");
        ReflectionTestUtils.setField(s, "maxNumber", maxNumber);
        ReflectionTestUtils.setField(s, "expirySeconds", expirySeconds);
        ReflectionTestUtils.setField(s, "enabled", enabled);
        ReflectionTestUtils.invokeMethod(s, "init");
        return s;
    }

    /** Resuelve el proof-of-work por fuerza bruta (maxNumber pequeño en los tests) y arma el payload. */
    private String solve(CaptchaChallengeDtoOut c) {
        long number = -1;
        for (long n = 0; n <= c.maxnumber(); n++) {
            if (sha256Hex(c.salt() + n).equals(c.challenge())) {
                number = n;
                break;
            }
        }
        String json = "{\"algorithm\":\"" + c.algorithm() + "\",\"challenge\":\"" + c.challenge()
                + "\",\"number\":" + number + ",\"salt\":\"" + c.salt() + "\",\"signature\":\"" + c.signature() + "\"}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String in) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(in.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void retoLegitimoSeResuelveYValidaUnaSolaVez() {
        CaptchaService s = service(2000, 300, true);
        String payload = solve(s.createChallenge());

        assertThat(s.verify(payload)).isTrue();
        // Segundo intento con el mismo payload: rechazado (anti-reutilización).
        assertThat(s.verify(payload)).isFalse();
    }

    @Test
    void numeroManipuladoNoValida() {
        CaptchaService s = service(2000, 300, true);
        String payload = solve(s.createChallenge());
        String tampered = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
                .replaceFirst("\"number\":\\d+", "\"number\":999999");
        assertThat(s.verify(Base64.getEncoder().encodeToString(tampered.getBytes(StandardCharsets.UTF_8)))).isFalse();
    }

    @Test
    void firmaManipuladaNoValida() {
        CaptchaService s = service(2000, 300, true);
        CaptchaChallengeDtoOut c = s.createChallenge();
        String json = new String(Base64.getDecoder().decode(solve(c)), StandardCharsets.UTF_8)
                .replace(c.signature(), "0000" + c.signature().substring(4));
        assertThat(s.verify(Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8)))).isFalse();
    }

    @Test
    void retoCaducadoNoValida() {
        CaptchaService s = service(2000, -10, true); // 'expires' ya en el pasado al crearlo
        assertThat(s.verify(solve(s.createChallenge()))).isFalse();
    }

    @Test
    void deshabilitadoAceptaCualquierCosa() {
        CaptchaService s = service(2000, 300, false);
        assertThat(s.verify(null)).isTrue();
        assertThat(s.verify("basura")).isTrue();
    }

    @Test
    void payloadVacioONuloNoValidaCuandoEstaActivo() {
        CaptchaService s = service(2000, 300, true);
        assertThat(s.verify(null)).isFalse();
        assertThat(s.verify("")).isFalse();
        assertThat(s.verify("no-es-base64-valido!!")).isFalse();
    }
}
