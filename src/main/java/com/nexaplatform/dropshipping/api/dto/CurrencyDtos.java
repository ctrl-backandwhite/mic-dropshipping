package com.nexaplatform.dropshipping.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;

public final class CurrencyDtos {
    private CurrencyDtos() {}

    public record CurrencyView(
            String code,
            String name,
            String symbol,
            String countryCode,
            String flagEmoji,
            String locale,
            BigDecimal rateVsUsd,
            boolean active,
            Instant lastSyncedAt
    ) {}

    public record UpdateRateRequest(
            @NotNull @Positive BigDecimal rateVsUsd,
            Boolean active
    ) {}

    public record SyncResult(int updated, String message) {}

    public record PriceView(
            BigDecimal amount,
            String currency,
            String symbol,
            BigDecimal amountUsd
    ) {}
}
