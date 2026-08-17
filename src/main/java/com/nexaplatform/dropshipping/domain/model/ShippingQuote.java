package com.nexaplatform.dropshipping.domain.model;

import java.util.List;

/**
 * Cotización de envío para un destino. {@code supported=false} cuando el país no está en la cobertura
 * del transportista (no se puede enviar ahí). El importe va en céntimos USD (la moneda canónica; el
 * frontend lo convierte a la divisa activa como el resto de precios).
 *
 * <p>{@code options} son las formas de envío entre las que el cliente puede elegir, ya filtradas —fuera
 * las que no pueden cumplir el DDP— y ordenadas de más barata a más cara. {@code amountUsdCents} es la
 * primera de ellas, que es lo que se cobra si el cliente no elige nada. Viene vacía cuando la tarifa
 * sale de la tabla de zonas y no del transportista: ahí no hay entre qué elegir.
 */
public record ShippingQuote(boolean supported, String countryCode, int amountUsdCents, String carrier,
        String serviceName, int etaMinDays, int etaMaxDays, String zone, List<ShippingOption> options) {

    /**
     * Cotización sin opciones que elegir: la de la tabla de zonas de respaldo. Mantiene la forma anterior
     * para no obligar a cambiar a quien no ofrece alternativas.
     */
    public ShippingQuote(boolean supported, String countryCode, int amountUsdCents, String carrier,
            String serviceName, int etaMinDays, int etaMaxDays, String zone) {
        this(supported, countryCode, amountUsdCents, carrier, serviceName, etaMinDays, etaMaxDays, zone,
                List.of());
    }

    /** Destino no cubierto por el transportista. */
    public static ShippingQuote unsupported(String countryCode) {
        return new ShippingQuote(false, countryCode, 0, null, null, 0, 0, null, List.of());
    }
}
