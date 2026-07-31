package com.nexaplatform.dropshipping.api.exception;

/**
 * El evento de la pasarela llegó bien pero no se pudo procesar.
 *
 * <p>Existe para que el webhook responda con error y el proveedor REINTENTE. Antes cualquier fallo al
 * procesar se tragaba y se devolvía {@code "ok"} con un 200: Stripe y PayPal daban el evento por
 * entregado y no lo reenviaban nunca más. El dinero seguía cobrado en la pasarela mientras el pedido no
 * pasaba a PAID —o la recarga no se acreditaba—, y del incidente solo quedaba una línea de log.
 *
 * <p>No se usa para eventos que no nos incumben ni para cargas mal formadas: eso no se arregla
 * reintentando, así que ahí se sigue devolviendo 200 y el proveedor deja de insistir.
 */
public class WebhookProcessingException extends RuntimeException {

    public WebhookProcessingException(String provider, String eventType, Throwable cause) {
        super("No se pudo procesar el evento " + eventType + " de " + provider
                + "; se devuelve error para que la pasarela lo reintente", cause);
    }
}
