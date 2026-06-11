package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "currency_rate")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CurrencyRateEntity extends BaseEntity {

    @Column(nullable = false, unique = true, length = 8)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 8)
    private String symbol;

    @Column(name = "country_code", length = 4)
    private String countryCode;

    @Column(name = "flag_emoji", length = 8)
    private String flagEmoji;

    @Column(length = 12)
    private String locale;

    @Column(name = "rate_vs_usd", nullable = false, precision = 18, scale = 8)
    private BigDecimal rateVsUsd;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;
}
