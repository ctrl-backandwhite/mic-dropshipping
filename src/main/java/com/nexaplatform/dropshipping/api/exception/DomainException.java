package com.nexaplatform.dropshipping.api.exception;

import java.util.List;

/** Domain invariant/state conflict. Mapped to HTTP 409 by {@link GlobalExceptionHandler}. */
public class DomainException extends BaseException {

    private static final String DEFAULT_CODE = "DM001";

    public DomainException(String message) {
        super(DEFAULT_CODE, message);
    }

    public DomainException(String code, String message) {
        super(code, message);
    }

    public DomainException(String code, List<String> detail) {
        super(code, detail);
    }
}
