package com.nexaplatform.dropshipping.infrastructure.integration.currency;

/**
 * ThreadLocal carrier for the active display currency code (ISO 4217). Populated by
 * {@link CurrencyRequestFilter} from the {@code X-Currency} HTTP header and cleared
 * after each request.
 */
public final class CurrencyHolder {

    private static final String DEFAULT = "USD";
    private static final ThreadLocal<String> CURRENT = ThreadLocal.withInitial(() -> DEFAULT);

    private CurrencyHolder() {
    }

    public static String get() {
        String code = CURRENT.get();
        return code == null || code.isBlank() ? DEFAULT : code.toUpperCase();
    }

    public static void set(String code) {
        CURRENT.set(code == null || code.isBlank() ? DEFAULT : code.trim().toUpperCase());
    }

    public static void clear() {
        CURRENT.remove();
    }
}
