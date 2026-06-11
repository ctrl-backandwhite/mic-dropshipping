package com.nexaplatform.dropshipping.domain.model;

import com.nexaplatform.dropshipping.domain.enums.PaymentMethod;
import com.nexaplatform.dropshipping.domain.enums.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Pure domain model for a payment (aggregate root). Carries the flattened
 * {@code userId}/{@code walletId} (resolved to managed relations by the
 * repository adapter) plus the read-only {@code userEmail} enrichment filled by
 * the use case for audit logging.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    private UUID id;
    private UUID userId;
    private UUID walletId;
    private UUID orderId;
    private String purpose;
    private PaymentMethod method;
    private PaymentStatus status;
    private BigDecimal amountDisplay;
    private String currencyDisplay;
    private long amountUsdCents;
    private String settlementCurrency;
    private BigDecimal settlementAmount;
    private String provider;
    private String providerRef;
    private Map<String, Object> providerResponse;
    private String idempotencyKey;
    private String cryptoAddress;
    private String cryptoChain;
    private Instant cryptoExpiresAt;
    private String qrUrl;
    private String errorMessage;

    // Read-only enrichment (filled by the use case from the user relation).
    private String userEmail;

    private Instant createdAt;
    private Instant updatedAt;
    private String createdBy;
    private String updatedBy;
}
