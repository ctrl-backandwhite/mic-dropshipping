package com.nexaplatform.dropshipping.application.service;

/**
 * País efectivo del comprador para la resolución del margen por país (ThreadLocal), análogo a
 * {@link PricingChannelHolder}.
 *
 * <p>Vacío por defecto: sin país el {@code MarginService} usa las reglas sin país (comportamiento actual).
 * Lo puebla un filtro de request a partir del país de registro del usuario y/o la cabecera de país del
 * proxy/CDN. Se limpia al final del request.
 */
public final class PricingCountryHolder {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private PricingCountryHolder() {
    }

    /** País ISO-2 en mayúsculas, o {@code null} si no se conoce (→ reglas sin país). */
    public static String get() {
        return CURRENT.get();
    }

    public static void set(String countryCode) {
        if (countryCode == null || countryCode.isBlank()) {
            CURRENT.remove();
        } else {
            CURRENT.set(countryCode.trim().toUpperCase());
        }
    }

    public static void clear() {
        CURRENT.remove();
    }
}
