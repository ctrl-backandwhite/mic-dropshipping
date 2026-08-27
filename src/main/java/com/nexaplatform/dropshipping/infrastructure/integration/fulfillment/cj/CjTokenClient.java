package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.cj;

/**
 * Las dos puertas de autenticación de CJ.
 *
 * <p>Es una interfaz y no una llamada directa para que la política de renovación
 * ({@link CjAuthService}) se pueda probar sin red: cuándo se pide un token y cuándo se reutiliza es una
 * decisión con dinero detrás —CJ limita a una llamada por segundo— y merece pruebas propias.
 */
public interface CjTokenClient {

    /** Autenticación con la clave de la cuenta. Es la vía cara: se usa solo si no hay refresco válido. */
    CjToken obtenerConApiKey(String apiKey);

    /** Renovación con el refresco guardado. */
    CjToken refrescar(String refreshToken);
}
