package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The commission earned for a conversion (DROP-646). PENDING→APPROVED→PAID, or REJECTED. */
@Entity
@Table(name = "affiliate_commission")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffiliateCommissionEntity extends BaseEntity {

    @Column(name = "affiliate_id", nullable = false, columnDefinition = "uuid")
    private UUID affiliateId;

    @Column(name = "conversion_id", nullable = false, unique = true, columnDefinition = "uuid")
    private UUID conversionId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(nullable = false, precision = 6, scale = 3)
    private BigDecimal percentage;

    /** PENDING, APPROVED, PAID, REJECTED. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** Wallet transaction id once the commission is credited (payout). */
    @Column(name = "wallet_tx_id", columnDefinition = "uuid")
    private UUID walletTxId;

    @Column(length = 300)
    private String note;
}
