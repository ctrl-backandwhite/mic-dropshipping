package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** A referral click attribution (cookie/last-click) within the attribution window (DROP-645). */
@Entity
@Table(name = "affiliate_attribution")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffiliateAttributionEntity extends BaseEntity {

    @Column(name = "referral_code_id", nullable = false, columnDefinition = "uuid")
    private UUID referralCodeId;

    @Column(name = "affiliate_id", nullable = false, columnDefinition = "uuid")
    private UUID affiliateId;

    /** Anonymous visitor cookie token (set before the user is known). */
    @Column(name = "visitor_token", length = 80)
    private String visitorToken;

    /** Resolved once the visitor logs in / registers. */
    @Column(name = "referred_user_id", columnDefinition = "uuid")
    private UUID referredUserId;

    @Column(name = "clicked_at", nullable = false)
    private Instant clickedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
