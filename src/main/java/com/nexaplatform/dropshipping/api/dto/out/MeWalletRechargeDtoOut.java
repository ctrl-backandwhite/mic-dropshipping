package com.nexaplatform.dropshipping.api.dto.out;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of initiating a wallet recharge. Replaces the legacy
 * {@code WalletDtos.RechargeResponse} record; field names are kept identical.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeWalletRechargeDtoOut {

    @Schema(description = "Payment id")
    private UUID paymentId;

    @Schema(description = "Payment method")
    private String method;

    @Schema(description = "Payment status")
    private String status;

    @Schema(description = "Amount in USD cents")
    private long amountUsdCents;

    @Schema(description = "Provider name")
    private String provider;

    @Schema(description = "Provider reference")
    private String providerRef;

    @Schema(description = "Stripe client secret")
    private String clientSecret;

    @Schema(description = "PayPal approve URL")
    private String approveUrl;

    @Schema(description = "USDT deposit address")
    private String cryptoAddress;

    @Schema(description = "USDT chain")
    private String cryptoChain;

    @Schema(description = "QR code URL")
    private String qrUrl;

    @Schema(description = "Expiry timestamp")
    private Instant expiresAt;
}
