package com.nexaplatform.dropshipping.api.exception;

import lombok.Getter;

import java.util.List;

/**
 * Canonical base for all domain/application exceptions (mirrors the core's
 * {@code com.backandwhite.common.exception.BaseException}, adapted to the
 * monolith). Carries a stable {@code code} and optional {@code detail} list;
 * the HTTP status is decided per exception type in {@link GlobalExceptionHandler}.
 */
@Getter
public class BaseException extends RuntimeException {

    private final String code;
    private final transient List<String> detail;

    public BaseException(String code, String message) {
        super(message);
        this.code = code;
        this.detail = null;
    }

    public BaseException(String code, List<String> detail) {
        this.code = code;
        this.detail = detail;
    }

    public BaseException(String message, String code, List<String> detail) {
        super(message);
        this.code = code;
        this.detail = detail;
    }
}
