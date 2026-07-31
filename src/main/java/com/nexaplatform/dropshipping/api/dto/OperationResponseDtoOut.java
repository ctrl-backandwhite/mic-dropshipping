package com.nexaplatform.dropshipping.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Canonical result for command/operation endpoints that do not return a
 * resource (mirrors the core's {@code OperationResponseDtoOut}).
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperationResponseDtoOut {

    @Schema(example = "OK")
    private String code;

    @Schema(example = "Operation completed successfully")
    private String message;

    @Schema(description = "Additional details")
    private List<String> details;

    @Schema(description = "Response timestamp")
    private ZonedDateTime dateTime;

    public static OperationResponseDtoOut ok(String message) {
        return OperationResponseDtoOut.builder().code("OK").message(message).details(List.of())
                .dateTime(ZonedDateTime.now(ZoneOffset.UTC)).build();
    }

    public static OperationResponseDtoOut ok(String code, String message) {
        return OperationResponseDtoOut.builder().code(code).message(message).details(List.of())
                .dateTime(ZonedDateTime.now(ZoneOffset.UTC)).build();
    }

    public static OperationResponseDtoOut error(String code, String message, List<String> details) {
        return OperationResponseDtoOut.builder().code(code).message(message)
                .details(details != null ? details : List.of()).dateTime(ZonedDateTime.now(ZoneOffset.UTC)).build();
    }
}
