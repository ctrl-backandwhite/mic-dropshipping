package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;

/** Output view of a currency rate. */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrencyDtoOut {

    @Schema(description = "ISO currency code", example = "USD")
    private String code;

    @Schema(description = "Currency display name", example = "US Dollar")
    private String name;

    @Schema(description = "Currency symbol", example = "$")
    private String symbol;

    @Schema(description = "ISO country code", example = "US")
    private String countryCode;

    @Schema(description = "Flag emoji")
    private String flagEmoji;

    @Schema(description = "Locale used for formatting", example = "en-US")
    private String locale;

    @Schema(description = "Exchange rate vs USD")
    private BigDecimal rateVsUsd;

    @Schema(description = "Whether the currency is active")
    private boolean active;

    @Schema(description = "Last time the rate was synced")
    private Instant lastSyncedAt;
}
