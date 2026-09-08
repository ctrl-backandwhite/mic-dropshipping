package com.nexaplatform.dropshipping.domain.enums;

/**
 * Desde dónde se abrió un cobro y, por tanto, a dónde hay que devolver a la persona cuando la
 * pasarela termine.
 *
 * <p>Es un <b>identificador cerrado</b>, no una URL. El cliente manda {@code "mobile"} y el servidor
 * traduce ese valor a una dirección que él mismo tiene configurada. Aceptar la dirección de vuelta
 * directamente —aunque se validara contra una lista— convertiría el cobro en un redirector abierto
 * en cuanto la validación tuviera un hueco: bastaría con enviar al comprador al dominio de quien
 * atacara justo después de aprobar el pago. Con un enum no hay hueco posible: lo que no está aquí,
 * no existe. Mismo criterio que {@code OAuthClientTarget} en el login social.
 *
 * <p>Se guarda en el pago porque la respuesta de la pasarela llega DESPUÉS, en otra petición, y para
 * entonces ya no queda ninguna cabecera que mirar.
 *
 * <p>Un valor desconocido, ausente o mal escrito cae en {@link #WEB}, que es el comportamiento que
 * había antes de existir la aplicación móvil.
 */
public enum PaymentClientTarget {

    WEB("web"),
    MOBILE("mobile");

    private final String code;

    PaymentClientTarget(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Resuelve el identificador recibido del cliente; cualquier cosa que no reconozca es {@link #WEB}. */
    public static PaymentClientTarget from(String raw) {
        if (raw == null || raw.isBlank()) {
            return WEB;
        }
        String normalized = raw.trim().toLowerCase();
        for (PaymentClientTarget target : values()) {
            if (target.code.equals(normalized)) {
                return target;
            }
        }
        return WEB;
    }
}
