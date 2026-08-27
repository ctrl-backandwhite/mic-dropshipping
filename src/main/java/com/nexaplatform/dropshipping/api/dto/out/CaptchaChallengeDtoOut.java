package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;

/**
 * Reto ALTCHA (proof-of-work) que se entrega al cliente. El navegador debe encontrar el {@code number}
 * en {@code [0, maxnumber]} tal que {@code SHA-256(salt + number)} coincida con {@code challenge}, y
 * devolver la solución firmada. El {@code signature} es un HMAC del servidor: impide que un cliente se
 * fabrique retos propios (el servidor comprueba que el reto lo emitió él).
 */
@Builder
public record CaptchaChallengeDtoOut(String algorithm, String challenge, long maxnumber, String salt,
        String signature) {
}
