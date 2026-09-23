package com.nexaplatform.dropshipping.infrastructure.email;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Clasifica el fallo de un envío SMTP como TEMPORAL (merece reintento diferido) o PERMANENTE
 * (reintentarlo es inútil, se descarta ya).
 *
 * <p>Motivación: proveedores como Hostinger devuelven {@code 451 4.7.1 Ratelimit ... exceeded} cuando
 * se supera su tope de correos salientes. Es un rechazo <b>temporal</b>: reintentando 5 veces seguidas
 * en un minuto todos los intentos caen en la misma ventana de bloqueo y el correo se pierde. Ante un
 * temporal hay que esperar (minutos/horas) y volver; ante un permanente (buzón inexistente, dirección
 * inválida — códigos 5xx) no.
 *
 * <p>La decisión se toma sobre el <b>texto</b> del error (mensaje de la excepción y sus causas), que en
 * Spring/Angus incluye la línea {@code "Failed messages: ...SMTPSendFailedException: 451 4.7.1 ..."}.
 * Así no se acopla a clases concretas del proveedor SMTP. Ante la duda, se trata como TEMPORAL: es
 * preferible reintentar (con un tope de antigüedad) a descartar un correo que sí se podría entregar.
 */
final class SmtpFailureClassifier {

    enum Kind {
        TRANSIENT, PERMANENT
    }

    /** Palabras que delatan un rechazo temporal aunque el código no se reconozca. */
    private static final String[] TRANSIENT_HINTS = {"ratelimit", "rate limit", "too many", "try again", "temporar",
            "throttl", "greylist", "grey-list", "timed out", "timeout", "connection", "unavailable, try", "4.7.1",
            "resources temporarily"};

    /** Primer código de estado SMTP de tres dígitos que empiece por 4 (temporal) o 5 (permanente). */
    private static final Pattern SMTP_CODE = Pattern.compile("\\b([45]\\d\\d)\\b");

    private SmtpFailureClassifier() {
    }

    static Kind classify(Throwable error) {
        String text = collectMessages(error).toLowerCase(Locale.ROOT);
        for (String hint : TRANSIENT_HINTS) {
            if (text.contains(hint)) {
                return Kind.TRANSIENT;
            }
        }
        Matcher matcher = SMTP_CODE.matcher(text);
        while (matcher.find()) {
            char first = matcher.group(1).charAt(0);
            if (first == '5') {
                return Kind.PERMANENT;
            }
            if (first == '4') {
                return Kind.TRANSIENT;
            }
        }
        // Desconocido: reintentar (con tope de antigüedad en el llamador), nunca perderlo por defecto.
        return Kind.TRANSIENT;
    }

    /** Concatena el mensaje de la excepción y el de toda su cadena de causas (con freno anti-ciclos). */
    private static String collectMessages(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable current = error;
        int guard = 0;
        while (current != null && guard++ < 20) {
            if (current.getMessage() != null) {
                sb.append(current.getMessage()).append(' ');
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return sb.toString();
    }
}
