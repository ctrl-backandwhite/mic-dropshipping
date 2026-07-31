package com.nexaplatform.dropshipping.application.usecase;

import java.math.BigDecimal;
import java.util.List;

/**
 * Opciones de recarga en la moneda ACTIVA del usuario: importes preestablecidos ya redondeados y
 * formateados por el backend (el frontend solo los pinta y envía el importe elegido). {@code symbol}
 * es el símbolo de la divisa activa.
 */
public record RechargeOptions(String currency, String symbol, List<Preset> presets) {

    /** Un importe preestablecido: {@code amount} en la divisa activa (lo que se envía) + su texto formateado. */
    public record Preset(BigDecimal amount, String formatted) {
    }
}
