package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

/**
 * Fallo al crear el envío en el transportista, clasificado por si tiene sentido reintentarlo.
 *
 * <p>La distinción es la que decide el comportamiento del sondeo: un {@link Kind#TRANSIENT} se reintenta
 * con espera creciente porque se resuelve solo (el gateway va lento, la guía todavía se está propagando),
 * mientras que un {@link Kind#PERMANENT} se abandona de inmediato porque reintentarlo no lo va a arreglar
 * —el bulto no cabe en el canal contratado, faltan datos aduaneros— y solo sirve para llenar el log y
 * retrasar el aviso al admin.
 */
public class FulfillmentFailure extends RuntimeException {

    /** ¿Reintentar tiene alguna posibilidad de funcionar? */
    public enum Kind {
        /** Se resuelve solo con el tiempo: red, timeout, 5xx, dato aún no propagado. */
        TRANSIENT,
        /** Requiere que alguien cambie algo (canal, peso, declaración): reintentar es inútil. */
        PERMANENT
    }

    /**
     * Códigos de negocio de YunExpress que NO se arreglan reintentando. El resto —incluidos los fallos de
     * red y los que no reconocemos— se tratan como transitorios: rendirse de más deja un envío sin crear
     * que nadie vuelve a intentar, y eso es peor que un reintento de sobra.
     */
    private enum PermanentCode {
        /** Order rule verification failed: peso/valor/datos fuera de lo que admite el canal. */
        RULE_VERIFICATION("02039171"),
        /** Parámetros de la petición inválidos. */
        INVALID_REQUEST("02030002"),
        /** El producto logístico indicado no existe o no está contratado. */
        UNKNOWN_PRODUCT("02030008"),
        /** Guía duplicada: ya existe un envío para ese número de cliente. */
        DUPLICATED("02030014");

        private final String code;

        PermanentCode(String code) {
            this.code = code;
        }

        static boolean matches(String message) {
            if (message == null) {
                return false;
            }
            for (PermanentCode value : values()) {
                if (message.contains(value.code)) {
                    return true;
                }
            }
            return false;
        }
    }

    private final transient Kind kind;

    public FulfillmentFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public boolean isPermanent() {
        return kind == Kind.PERMANENT;
    }

    /** Fallo clasificado a partir del mensaje del transportista. */
    public static FulfillmentFailure from(String message) {
        return new FulfillmentFailure(
                PermanentCode.matches(message) ? Kind.PERMANENT : Kind.TRANSIENT, message);
    }

    /**
     * Clasifica una excepción cualquiera surgida al crear el envío. Lo que ya viene clasificado se
     * respeta; un fallo de red o un error inesperado se considera transitorio.
     */
    public static FulfillmentFailure of(RuntimeException e) {
        if (e instanceof FulfillmentFailure failure) {
            return failure;
        }
        return from(e.getMessage() != null ? e.getMessage() : e.toString());
    }
}
