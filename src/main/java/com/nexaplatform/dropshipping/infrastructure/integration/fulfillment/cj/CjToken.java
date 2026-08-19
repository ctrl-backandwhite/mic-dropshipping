package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

import java.time.Instant;

/**
 * Lo que CJ devuelve al autenticarse.
 *
 * @param accessToken     el que viaja en la cabecera {@code CJ-Access-Token}
 * @param refreshToken    permite renovar sin volver a mandar la clave
 * @param openId          identificador de la cuenta; es el secreto de la firma del webhook
 * @param refreshExpiraEn hasta cuándo vale el refresco; pasada esa fecha toca autenticarse con la clave
 */
public record CjToken(String accessToken, String refreshToken, String openId, Instant refreshExpiraEn) {
}
