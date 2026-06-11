package com.nexaplatform.dropshipping.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.With;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Pure domain model for a wallet ledger entry (nested/secondary sub-entity of
 * {@link Wallet}). Carries the flattened {@code walletId}; the managed wallet
 * relation is resolved by the repository adapter.
 */
@Data
@With
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletTransaction {

    private UUID id;
    private UUID walletId;
    private String kind;
    private long amountUsdCents;
    private long balanceAfterCents;
    private UUID paymentId;
    private UUID orderId;
    private String idempotencyKey;
    private String status;
    private String description;
    private Map<String, Object> metadata;
    private Instant createdAt;
}
