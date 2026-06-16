package com.nexaplatform.dropshipping.domain.model;

/**
 * Cotización de envío de Cainiao para un destino. {@code supported=false} cuando el país no está en la
 * cobertura de Cainiao (no se puede enviar ahí). El importe va en céntimos USD (la moneda canónica;
 * el frontend lo convierte a la divisa activa como el resto de precios).
 */
public record ShippingQuote(boolean supported, String countryCode, int amountUsdCents, String carrier,
        String serviceName, int etaMinDays, int etaMaxDays, String zone) {

    /** Destino no cubierto por Cainiao. */
    public static ShippingQuote unsupported(String countryCode) {
        return new ShippingQuote(false, countryCode, 0, null, null, 0, 0, null);
    }
}
