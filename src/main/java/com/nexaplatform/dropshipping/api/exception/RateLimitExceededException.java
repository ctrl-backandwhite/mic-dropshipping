package com.nexaplatform.dropshipping.api.exception;

/** Rate-limit quota exceeded. Mapped to HTTP 429 by {@link GlobalExceptionHandler}. */
public class RateLimitExceededException extends BaseException {

    private static final String DEFAULT_CODE = "RL001";

    public RateLimitExceededException(String message) {
        super(DEFAULT_CODE, message);
    }
}
