package com.nexaplatform.dropshipping.api.exception;

import java.util.List;

/**
 * Unique/state conflict. Specialization of {@link DomainException} (HTTP 409).
 *
 * <p>La profundidad que denuncia java:S110 son las clases de {@code java.lang}
 * (RuntimeException → Exception → Throwable): la jerarquía propia solo añade dos niveles. Acortarla
 * cambiaría la respuesta HTTP, porque {@code GlobalExceptionHandler} devuelve el 409 justamente por
 * heredar de {@link DomainException}.
 */
@SuppressWarnings("java:S110")
public class ConflictException extends DomainException {

    private static final String DEFAULT_CODE = "CF001";

    public ConflictException(String message) {
        super(DEFAULT_CODE, message);
    }

    public ConflictException(String code, List<String> detail) {
        super(code, detail);
    }
}
