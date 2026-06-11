package com.nexaplatform.dropshipping.api.exception;

import java.util.List;

/** Requested entity does not exist. Mapped to HTTP 404 by {@link GlobalExceptionHandler}. */
public class NotFoundException extends BaseException {

    private static final String DEFAULT_CODE = "ENF001";

    public NotFoundException(String message) {
        super(DEFAULT_CODE, message);
    }

    public NotFoundException(String code, String message) {
        super(code, message);
    }

    public NotFoundException(String code, List<String> detail) {
        super(code, detail);
    }
}
