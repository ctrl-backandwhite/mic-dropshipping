package com.nexaplatform.dropshipping.infrastructure.security.oauth;

/**
 * Cliente que inicia un login social y, por tanto, a dónde hay que devolver el resultado.
 *
 * <p>Es un <b>identificador cerrado</b>, no una URL. El cliente manda {@code ?client=mobile} y el servidor
 * traduce ese valor a una dirección que él mismo tiene configurada. Aceptar la URL de destino directamente
 * —aunque se validara contra una lista— convertiría este endpoint en un redirector abierto en cuanto la
 * validación tuviera un hueco: un atacante enviaría a la víctima a su propio dominio con los tokens en el
 * fragmento. Con un enum no hay hueco posible: lo que no está aquí, no existe.
 *
 * <p>Un valor desconocido, ausente o mal escrito cae en {@link #WEB}, que es el comportamiento que había
 * antes de existir la aplicación móvil.
 */
public enum OAuthClientTarget {

    WEB("web"),
    MOBILE("mobile");

    private final String code;

    OAuthClientTarget(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Resuelve el identificador recibido del cliente; cualquier cosa que no reconozca es {@link #WEB}. */
    public static OAuthClientTarget from(String raw) {
        if (raw == null || raw.isBlank()) {
            return WEB;
        }
        String normalized = raw.trim().toLowerCase();
        for (OAuthClientTarget target : values()) {
            if (target.code.equals(normalized)) {
                return target;
            }
        }
        return WEB;
    }
}
