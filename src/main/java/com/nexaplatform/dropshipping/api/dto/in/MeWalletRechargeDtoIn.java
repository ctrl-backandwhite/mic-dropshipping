package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Request body to recharge the authenticated user's wallet. Replaces the legacy
 * {@code WalletDtos.RechargeRequest} record; field names and validation are kept identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeWalletRechargeDtoIn {

    @Schema(description = "Payment method: CARD, PAYPAL or USDT", example = "CARD")
    @NotNull
    @Pattern(regexp = "^(CARD|PAYPAL|USDT)$")
    private String method;

    @Schema(description = "Amount to recharge in USD cents (optional fallback; the backend derives it "
            + "from amountDisplay + currencyDisplay)")
    @Positive
    @Max(value = 10_000_000L, message = "Importe de recarga fuera de rango") // tope defensivo: 100.000 USD
    private Long amountUsdCents;

    @Schema(description = "USDT chain: TRC20, ERC20 or BEP20 (USDT only)")
    private String cryptoChain;

    @Schema(description = "Active display currency code (source of truth for the charge)")
    private String currencyDisplay;

    @Schema(description = "Amount the user entered in the display currency (source of truth)")
    @Positive
    @DecimalMax(value = "1000000", message = "Importe de recarga fuera de rango") // tope defensivo
    private BigDecimal amountDisplay;
}
