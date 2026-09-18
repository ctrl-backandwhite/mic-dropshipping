package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "affiliate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffiliateEntity extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private UserEntity user;

    @Column(nullable = false, unique = true, length = 40)
    private String code;
    @Column(name = "earnings_usd_cents", nullable = false)
    @Builder.Default
    private long earningsUsdCents = 0;
    @Column(name = "payout_usd_cents", nullable = false)
    @Builder.Default
    private long payoutUsdCents = 0;
    @Column(name = "referrals_count", nullable = false)
    @Builder.Default
    private int referralsCount = 0;
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    /** Lifecycle: PENDING, ACTIVE, SUSPENDED. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "ACTIVE";

    /** Per-affiliate commission override (percent); null → use the program default. */
    @Column(name = "commission_percent_override", precision = 6, scale = 3)
    private BigDecimal commissionPercentOverride;

    /** When the customer explicitly accepted the program terms (DROP-650). */
    @Column(name = "accepted_terms_at")
    private Instant acceptedTermsAt;

    @Builder.Default
    @Column(name = "payout_method", nullable = false, length = 20)
    private String payoutMethod = "WALLET";
    @Column(name = "bank_holder", length = 160)
    private String bankHolder;
    @Column(name = "bank_iban", length = 40)
    private String bankIban;
    @Column(name = "bank_bic", length = 16)
    private String bankBic;
    @Column(name = "paypal_email", length = 200)
    private String paypalEmail;
}
