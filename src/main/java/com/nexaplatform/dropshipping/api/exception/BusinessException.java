package com.nexaplatform.dropshipping.api.exception;

import java.util.List;

/** Business rule violation. Mapped to HTTP 422 by {@link GlobalExceptionHandler}. */
public class BusinessException extends BaseException {

    private static final String DEFAULT_CODE = "BR001";

    public BusinessException(String message) {
        super(DEFAULT_CODE, message);
    }

    public BusinessException(String code, String message) {
        super(code, message);
    }

    public BusinessException(String code, List<String> detail) {
        super(code, detail);
    }

    /**
     * Código catalogado + mensaje técnico + detalle. El usuario ve el texto localizado que
     * {@link ErrorCode} resuelve a partir del código; el mensaje y el detalle son para el registro y
     * para quien diagnostica —ahí es donde va el motivo literal que devuelve la pasarela—.
     */
    public BusinessException(String code, String message, List<String> detail) {
        super(message, code, detail);
    }
}
