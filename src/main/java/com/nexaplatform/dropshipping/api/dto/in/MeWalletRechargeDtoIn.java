package com.nexaplatform.dropshipping.api.dto.in;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Amount to recharge in USD cents")
    @NotNull
    @Positive
    private Long amountUsdCents;

    @Schema(description = "USDT chain: TRC20, ERC20 or BEP20 (USDT only)")
    private String cryptoChain;

    @Schema(description = "Display currency code")
    private String currencyDisplay;

    @Schema(description = "Amount in the display currency")
    private BigDecimal amountDisplay;
}
