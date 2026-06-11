package com.nexaplatform.dropshipping.api.exception;

import java.util.List;

/** Invalid argument (programmatic validation). Mapped to HTTP 400 by {@link GlobalExceptionHandler}. */
public class ArgumentException extends BaseException {

    private static final String DEFAULT_CODE = "AE001";

    public ArgumentException(String message) {
        super(DEFAULT_CODE, message);
    }

    public ArgumentException(String code, String message) {
        super(code, message);
    }

    public ArgumentException(String code, List<String> detail) {
        super(code, detail);
    }
}
