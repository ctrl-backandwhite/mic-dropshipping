package com.nexaplatform.dropshipping.api.exception;

import java.util.List;

/** Unique/state conflict. Specialization of {@link DomainException} (HTTP 409). */
public class ConflictException extends DomainException {

    private static final String DEFAULT_CODE = "CF001";

    public ConflictException(String message) {
        super(DEFAULT_CODE, message);
    }

    public ConflictException(String code, List<String> detail) {
        super(code, detail);
    }
}
