package com.nexaplatform.dropshipping.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class WalletDtos {
    private WalletDtos() {
    }

    public record WalletView(UUID id, long balanceUsdCents, long holdUsdCents, long availableUsdCents,
            String currencyDefault, String status, BigDecimal balanceDisplay, String displayCurrency,
            String displaySymbol) {
    }

    public record WalletTxView(UUID id, String kind, long amountUsdCents, long balanceAfterCents, String status,
            String description, UUID paymentId, UUID orderId, Instant createdAt) {
    }

    public record RechargeRequest(@NotNull @Pattern(regexp = "^(CARD|PAYPAL|USDT)$") String method,
            @NotNull @Positive Long amountUsdCents, String cryptoChain, // TRC20 | ERC20 | BEP20 (USDT only)
            String currencyDisplay, BigDecimal amountDisplay) {
    }

    public record RechargeResponse(UUID paymentId, String method, String status, long amountUsdCents, String provider,
            String providerRef, String clientSecret, // Stripe
            String approveUrl, // PayPal
            String cryptoAddress, // USDT
            String cryptoChain, String qrUrl, Instant expiresAt) {
    }

    public record AdjustRequest(@NotNull Long signedAmountUsdCents, @NotBlank String reason) {
    }
}
