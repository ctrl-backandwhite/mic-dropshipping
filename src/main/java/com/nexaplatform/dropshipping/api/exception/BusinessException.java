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
}
