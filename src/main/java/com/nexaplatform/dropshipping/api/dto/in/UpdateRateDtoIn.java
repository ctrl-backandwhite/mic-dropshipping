package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;

/** Input payload to override a currency rate and optionally toggle its active flag. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateRateDtoIn {

    @NotNull
    @Positive
    @Schema(description = "New exchange rate vs USD", example = "1.08")
    private BigDecimal rateVsUsd;

    @Schema(description = "Optional active flag override", example = "true")
    private Boolean active;
}
