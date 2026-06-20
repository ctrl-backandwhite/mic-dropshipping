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

    private static final Pattern CONSTRAINT = Pattern.compile("constraint\\s+\"?([a-zA-Z0-9_]+)\"?");
    private static final Pattern COLUMN = Pattern.compile("column\\s+\"?([a-zA-Z0-9_]+)\"?");
    private static final Pattern VARCHAR_LEN = Pattern.compile("character varying\\((\\d+)\\)");
    private static final Pattern DUP_DETAIL = Pattern.compile("Key \\(([^)]+)\\)=\\(([^)]+)\\)");

    private ErrorMessages() {
    }

    /** Mensaje humano para la excepción (o su causa raíz). Nunca devuelve SQL crudo. */
    public static String humanize(Throwable t) {
        if (t == null) {
            return "No se pudo completar la operación.";
        }
        // 1) Mensaje de negocio explícito (validaciones ya redactadas) → tal cual.
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof BaseException be && be.getMessage() != null && !be.getMessage().isBlank()) {
                return be.getMessage();
            }
        }
        String raw = rootMessage(t);
        if (raw == null || raw.isBlank()) {
            return "No se pudo completar la operación por un error inesperado.";
        }
        String low = raw.toLowerCase();

        // 2) Violación de unique/check con nombre de constraint mapeado.
        String constraint = firstGroup(CONSTRAINT, raw);
        if (constraint != null) {
            String mapped = ConstraintMessage.forConstraint(constraint);
            if (mapped != null) {
                return mapped;
            }
        }

        // 3) Patrones genéricos de PostgreSQL → mensaje claro.
        if (low.contains("duplicate key") || low.contains("ya existe") || low.contains("already exists")) {
            Matcher m = DUP_DETAIL.matcher(raw);
            if (m.find()) {
                return "Ya existe un registro con " + m.group(1) + " = " + m.group(2)
                        + ". Ese valor debe ser único.";
            }
            return "Ya existe un registro con esos datos (valor duplicado). Revisa los campos que deben ser únicos.";
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
            // Lote masivo: el detalle real suele estar en una causa con la constraint, ya cubierta arriba;
            // si llegamos aquí sin constraint reconocida, mensaje neutro.
            return "No se pudo guardar el registro por un conflicto de datos. Revisa los valores duplicados u obligatorios.";
        }

        // 4) Fallback limpio (sin SQL).
        return "No se pudo guardar por un conflicto de datos. Revisa los valores e inténtalo de nuevo.";
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
