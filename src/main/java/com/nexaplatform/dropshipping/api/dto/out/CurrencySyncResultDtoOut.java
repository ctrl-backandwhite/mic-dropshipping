package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

/** Result of a currency rate synchronization from the external provider. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencySyncResultDtoOut {

    @Schema(description = "Number of currency rates updated", example = "12")
    private int updated;

    @Schema(description = "Human-readable result message")
    private String message;
}
