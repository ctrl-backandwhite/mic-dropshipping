package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/** A sale attributed to an affiliate (DROP-645/646). One per order (idempotent). */
@Entity
@Table(name = "affiliate_conversion")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffiliateConversionEntity extends BaseEntity {

    @Column(name = "affiliate_id", nullable = false, columnDefinition = "uuid")
    private UUID affiliateId;

    @Column(name = "referral_code_id", columnDefinition = "uuid")
    private UUID referralCodeId;

    @Column(name = "referred_user_id", columnDefinition = "uuid")
    private UUID referredUserId;

    @Column(name = "order_id", nullable = false, unique = true, columnDefinition = "uuid")
    private UUID orderId;

    /** Order subtotal (no shipping/tax) the commission is computed on, in cents. */
    @Column(name = "base_amount_cents", nullable = false)
    private long baseAmountCents;

    @Column(nullable = false, length = 8)
    private String currency;

    /** PENDING, CONFIRMED, CANCELLED. */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";
}
