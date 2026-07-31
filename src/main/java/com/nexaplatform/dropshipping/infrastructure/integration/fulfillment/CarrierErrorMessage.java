package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

/**
 * Traducción al español de los rechazos del transportista.
 *
 * <p>YunExpress contesta en inglés (y a veces en chino), así que meter su mensaje tal cual en un aviso
 * escrito en español deja un correo a medias entre dos idiomas y difícil de accionar. Aquí se convierte
 * el código en una explicación en español que dice <b>qué hacer</b>; el texto original se conserva aparte
 * como detalle técnico, que es lo que hace falta si hay que abrir incidencia con el transportista.
 *
 * <p>Mismo criterio que {@code ConstraintMessage} para los errores de base de datos: el código es la
 * clave estable y el texto legible vive en el enum.
 */
public enum CarrierErrorMessage {

    RULE_VERIFICATION("02039171",
            "El paquete no cumple las reglas del canal contratado (peso, valor declarado o datos de la "
                    + "declaración). Revisa los límites del producto logístico o reparte el pedido."),
    INVALID_REQUEST("02030002",
            "El transportista ha rechazado los datos del envío por inválidos. Revisa dirección, "
                    + "declaración aduanera y medidas del paquete."),
    UNKNOWN_PRODUCT("02030008",
            "El canal indicado no existe o no está contratado en la cuenta. Revisa el código de producto "
                    + "logístico configurado."),
    DUPLICATED("02030014",
            "Ya existe un envío con ese número de pedido en el transportista. No se ha creado uno nuevo "
                    + "para no duplicar la guía."),
    TIMEOUT("02030012",
            "El transportista no ha respondido a tiempo. Suele resolverse solo; se reintentará."),
    ORDER_NOT_FOUND("02041002",
            "El transportista todavía no reconoce esa guía. Es normal justo después de crearla: tarda "
                    + "unos minutos en propagarse."),
    NO_PRICE("02060012",
            "El transportista no ofrece tarifa para ese destino y peso. Puede que el canal no cubra ese "
                    + "país o que falte el acuerdo comercial."),
    NO_PRICE_ALT("02060015",
            "El transportista no ofrece tarifa para ese destino y peso. Puede que el canal no cubra ese "
                    + "país o que falte el acuerdo comercial.");

    private final String code;
    private final String message;

    CarrierErrorMessage(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /**
     * Explicación en español del rechazo. Si el código no está catalogado se devuelve un texto genérico:
     * el motivo exacto sigue disponible en el detalle técnico, así que nunca se pierde información.
     */
    public static String humanize(String rawError) {
        if (rawError == null || rawError.isBlank()) {
            return "El transportista ha rechazado el envío sin indicar motivo.";
        }
        for (CarrierErrorMessage value : values()) {
            if (rawError.contains(value.code())) {
                return value.message();
            }
        }
        return "El transportista ha rechazado el envío. Consulta el detalle técnico para ver el motivo exacto.";
    }
}
