package com.nexaplatform.dropshipping.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Canonical response envelope (mirrors the core's {@code ApiResponseDtoOut}):
 * a typed {@code data} payload plus code/message/details/timestamp. Also used
 * as the body for error responses by {@link com.nexaplatform.dropshipping.api.exception.GlobalExceptionHandler}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponseDtoOut<T> {

    @Schema(description = "Operation status code", example = "SUCCESS")
    private String code;

    @Schema(description = "Descriptive operation message", example = "Operation completed successfully")
    private String message;

    @Schema(description = "Response payload")
    private T data;

    @Schema(description = "Additional details or specific errors")
    private List<String> details;

    @Schema(description = "Response timestamp")
    private ZonedDateTime timestamp;

    public static <T> ApiResponseDtoOut<T> success(T data, String message) {
        return ApiResponseDtoOut.<T>builder().code("SUCCESS").message(message).data(data)
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC)).build();
    }

    public static <T> ApiResponseDtoOut<T> success(String message) {
        return ApiResponseDtoOut.<T>builder().code("SUCCESS").message(message)
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC)).build();
    }

    public static <T> ApiResponseDtoOut<T> error(String code, String message, List<String> details) {
        return ApiResponseDtoOut.<T>builder().code(code).message(message).details(details)
                .timestamp(ZonedDateTime.now(ZoneOffset.UTC)).build();
    }
}
