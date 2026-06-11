package com.nexaplatform.dropshipping.api.dto.out;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * View of an order payment (partner B2B and customer B2C share this shape).
 * Field names mirror the legacy {@code PaymentView} record both controllers
 * exposed. {@code clientSecret}/{@code approveUrl} are projected from the
 * payment provider metadata; crypto fields apply to USDT only.
 */
@Value
@Builder
public class OrderPaymentDtoOut {

    UUID id;
    UUID orderId;
    String method;
    String status;
    long amountUsdCents;
    BigDecimal amountDisplay;
    String currencyDisplay;
    String provider;
    String providerRef;
    // CARD only
    String clientSecret;
    // PAYPAL only
    String approveUrl;
    // USDT only
    String cryptoAddress;
    String cryptoChain;
    String qrUrl;
    Instant cryptoExpiresAt;
    Instant createdAt;
}
