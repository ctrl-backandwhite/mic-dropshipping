package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Global affiliate program configuration — single row (DROP-643). */
@Entity
@Table(name = "affiliate_program_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AffiliateProgramConfigEntity extends BaseEntity {

    @Column(name = "default_percent", nullable = false, precision = 6, scale = 3)
    private BigDecimal defaultPercent;

    @Column(name = "attribution_window_days", nullable = false)
    private int attributionWindowDays;

    @Column(name = "return_period_days", nullable = false)
    private int returnPeriodDays;

    @Column(name = "min_payout_cents", nullable = false)
    private long minPayoutCents;

    @Column(nullable = false, length = 8)
    private String currency;

    /** LAST_CLICK or FIRST_CLICK. */
    @Column(name = "attribution_model", nullable = false, length = 20)
    @Builder.Default
    private String attributionModel = "LAST_CLICK";
}
