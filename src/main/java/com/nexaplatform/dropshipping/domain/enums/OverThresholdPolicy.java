package com.nexaplatform.dropshipping.domain.enums;

/**
 * Qué hacer cuando el valor intrínseco del pedido supera el umbral de minimis del país de destino y el
 * régimen simplificado (IOSS, VOEC, Low Value Goods) deja de aplicar.
 *
 * <ul>
 *   <li>{@link #SURCHARGE} — se vende aplicando el recargo de despacho formal (arancel estimado + tasa
 *       fija), de modo que el precio que paga el cliente cubre el sobrecoste real. Es el defecto.</li>
 *   <li>{@link #ALLOW} — se vende sin recargo: el comercio absorbe el sobrecoste.</li>
 *   <li>{@link #BLOCK} — no se permite el pedido a ese destino por encima del umbral.</li>
 * </ul>
 */
public enum OverThresholdPolicy {

    SURCHARGE, ALLOW, BLOCK;

    /** Convierte el texto persistido; cualquier valor desconocido o nulo cae a {@link #SURCHARGE}. */
    public static OverThresholdPolicy from(String value) {
        if (value == null || value.isBlank()) {
            return SURCHARGE;
        }
        try {
            return OverThresholdPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return SURCHARGE;
        }
    }
}
