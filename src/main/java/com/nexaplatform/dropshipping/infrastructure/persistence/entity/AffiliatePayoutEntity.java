package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A commission settlement / payout (DROP-651). REQUESTED → APPROVED/PAID or REJECTED. */
@Entity
@Table(name = "affiliate_payout")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffiliatePayoutEntity extends BaseEntity {

    @Column(name = "affiliate_id", nullable = false, columnDefinition = "uuid")
    private UUID affiliateId;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 8)
    private String currency;

    /** REQUESTED, APPROVED, PAID, REJECTED. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "REQUESTED";

    /** WALLET or EXTERNAL. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String method = "WALLET";

    @Column(name = "wallet_tx_id", columnDefinition = "uuid")
    private UUID walletTxId;

    @Column(name = "commission_count", nullable = false)
    @Builder.Default
    private int commissionCount = 0;

    @Column(length = 300)
    private String note;

    @Column(name = "requested_at")
    private Instant requestedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "dest_holder", length = 160)
    private String destHolder;
    @Column(name = "dest_iban", length = 40)
    private String destIban;
    @Column(name = "dest_bic", length = 16)
    private String destBic;
    @Column(name = "dest_paypal_email", length = 200)
    private String destPaypalEmail;
    @Column(name = "paid_reference", length = 200)
    private String paidReference;
    @Column(name = "paid_by", columnDefinition = "uuid")
    private UUID paidBy;
}
