package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

/** A shareable referral code/link owned by an affiliate (DROP-644). */
@Entity
@Table(name = "affiliate_referral_code")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffiliateReferralCodeEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "affiliate_id", nullable = false)
    private AffiliateEntity affiliate;

    @Column(nullable = false, unique = true, length = 40)
    private String code;

    @Column(length = 120)
    private String label;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private int clicks = 0;
}
