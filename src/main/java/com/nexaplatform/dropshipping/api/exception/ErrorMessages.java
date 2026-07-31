package com.nexaplatform.dropshipping.api.exception;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Convierte cualquier excepción en un mensaje claro y accionable para un usuario no técnico,
 * sin filtrar SQL crudo. Se usa tanto en el {@link GlobalExceptionHandler} (errores individuales)
 * como en los reportes por fila de las cargas masivas.
 *
 * <p>Estrategia:
 * <ol>
 *   <li>Si en la cadena de causas hay una excepción de negocio ({@link BaseException}), su mensaje
 *       ya es claro → se devuelve tal cual.</li>
 *   <li>Si es un error de base de datos, se identifica la constraint o el patrón (duplicado,
 *       not-null, texto demasiado largo, clave foránea, formato inválido) y se traduce con
 *       {@link ConstraintMessage} o un mensaje genérico entendible.</li>
 *   <li>En último caso, un mensaje limpio y neutro (nunca el stack/SQL).</li>
 * </ol>
 */
public final class ErrorMessages {

    private static final Pattern CONSTRAINT = Pattern.compile("constraint\\s+\"?(\\w+)\"?");
    private static final Pattern COLUMN = Pattern.compile("column\\s+\"?(\\w+)\"?");
    private static final Pattern VARCHAR_LEN = Pattern.compile("character varying\\((\\d+)\\)");
    private static final Pattern DUP_DETAIL = Pattern.compile("Key \\(([^)]+)\\)=\\(([^)]+)\\)");

    private ErrorMessages() {
    }

    /**
     * Mensaje humano para la excepción (o su causa raíz). Nunca devuelve SQL crudo.
     *
     * <p>El ORDEN de los pasos es la parte que importa: el mensaje de negocio gana siempre porque ya está
     * redactado para el usuario; después la constraint con nombre propio, que es la traducción más
     * precisa; y sólo si nada de eso encaja se cae a los patrones genéricos de PostgreSQL y al texto
     * neutro. Invertirlo daría el mensaje vago cuando existe el concreto.
     */
    public static String humanize(Throwable t) {
        if (t == null) {
            return "No se pudo completar la operación.";
        }
        String business = businessMessage(t);
        if (business != null) {
            return business;
        }
        String raw = rootMessage(t);
        if (raw == null || raw.isBlank()) {
            return "No se pudo completar la operación por un error inesperado.";
        }
        String mapped = mappedConstraintMessage(raw);
        if (mapped != null) {
            return mapped;
        }
        String pattern = postgresPatternMessage(raw);
        if (pattern != null) {
            return pattern;
        }
        return "No se pudo guardar por un conflicto de datos. Revisa los valores e inténtalo de nuevo.";
    }

    /** Primer mensaje de negocio de la cadena de causas (validaciones ya redactadas), o null si no hay. */
    private static String businessMessage(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof BaseException be && be.getMessage() != null && !be.getMessage().isBlank()) {
                return be.getMessage();
            }
        }
        return null;
    }

    /** Violación de unique/check cuyo nombre de constraint tenemos traducido, o null si no la reconocemos. */
    private static String mappedConstraintMessage(String raw) {
        String constraint = firstGroup(CONSTRAINT, raw);
        return constraint != null ? ConstraintMessage.forConstraint(constraint) : null;
    }

    /** Patrones genéricos de PostgreSQL traducidos a lenguaje llano. Null si ninguno encaja. */
    private static String postgresPatternMessage(String raw) {
        String low = raw.toLowerCase();
        if (low.contains("duplicate key") || low.contains("ya existe") || low.contains("already exists")) {
            return duplicateMessage(raw);
        }
        if (low.contains("not-null") || low.contains("null value in column")) {
            String col = firstGroup(COLUMN, raw);
            return col != null
                    ? "Falta un valor obligatorio en el campo '" + col + "'."
                    : "Falta un valor obligatorio.";
        }
        if (low.contains("value too long")) {
            String len = firstGroup(VARCHAR_LEN, raw);
            return len != null
                    ? "Un texto supera el largo máximo permitido (" + len + " caracteres). Acórtalo."
                    : "Un texto supera el largo máximo permitido. Acórtalo.";
        }
        if (low.contains("foreign key")) {
            return "Se hace referencia a un registro que no existe (categoría, proveedor o relación inválida).";
        }
        if (low.contains("check constraint")) {
            return "Un valor no cumple una regla de validación. Revisa los campos del registro.";
        }
        if (low.contains("invalid input syntax") || low.contains("invalid input value")
                || low.contains("could not parse") || low.contains("numberformat")) {
            return "Un valor tiene un formato inválido (número, fecha o identificador mal formado).";
        }
        if (low.contains("could not execute batch") || low.contains("batch entry")) {
            // Lote masivo: el detalle real suele estar en una causa con la constraint, ya cubierta antes;
            // si llegamos aquí sin constraint reconocida, mensaje neutro.
            return "No se pudo guardar el registro por un conflicto de datos. Revisa los valores duplicados u obligatorios.";
        }
        return null;
    }

    /** Duplicado: si PostgreSQL detalla la clave y el valor, se los damos al usuario para que sepa cuál cambiar. */
    private static String duplicateMessage(String raw) {
        Matcher m = DUP_DETAIL.matcher(raw);
        if (m.find()) {
            return "Ya existe un registro con " + m.group(1) + " = " + m.group(2) + ". Ese valor debe ser único.";
        }
        return "Ya existe un registro con esos datos (valor duplicado). Revisa los campos que deben ser únicos.";
    }

    /** Mensaje de la causa más profunda. */
    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage();
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
