package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Authenticated user's wallet projection. Replaces the legacy
 * {@code WalletDtos.WalletView} record; field names are kept identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeWalletDtoOut {

    @Schema(description = "Wallet id")
    private UUID id;

    @Schema(description = "Current balance in USD cents")
    private long balanceUsdCents;

    @Schema(description = "Amount on hold in USD cents")
    private long holdUsdCents;

    @Schema(description = "Available balance in USD cents")
    private long availableUsdCents;

    @Schema(description = "Default wallet currency")
    private String currencyDefault;

    @Schema(description = "Wallet status")
    private String status;

    @Schema(description = "Balance converted to the display currency")
    private BigDecimal balanceDisplay;

    @Schema(description = "Display currency code")
    private String displayCurrency;

    @Schema(description = "Display currency symbol")
    private String displaySymbol;

    @Schema(description = "Balance in the active currency, formatted by the backend (country convention)")
    private String balanceFormatted;

    @Schema(description = "Canonical USD balance, formatted by the backend (country convention)")
    private String balanceUsdFormatted;

    @Schema(description = "Held (USD) amount, formatted by the backend (country convention)")
    private String holdUsdFormatted;
}
