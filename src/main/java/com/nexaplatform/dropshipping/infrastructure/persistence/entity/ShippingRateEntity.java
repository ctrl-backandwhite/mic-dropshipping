package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Shipping rate for a (supplier, destination country, method). Cost is computed as
 * baseCents + perKgCents * weightKg, capped at min/max weight when those columns are set.
 */
@Entity
@Table(name = "shipping_rate")
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShippingRateEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private SupplierEntity supplier;

    @Column(name = "country_code", nullable = false, length = 2)
    private String countryCode;

    /** STANDARD | EXPRESS | SEA | AIR | PICKUP */
    @Column(nullable = false, length = 20)
    private String method;

    @Column(length = 60)
    private String carrier;

    @Column(name = "transit_days_min", nullable = false)
    private int transitDaysMin;

    @Column(name = "transit_days_max", nullable = false)
    private int transitDaysMax;

    @Column(name = "base_cents", nullable = false)
    private int baseCents;

    @Column(name = "per_kg_cents", nullable = false)
    private int perKgCents;

    @Column(name = "min_weight_grams")
    private Integer minWeightGrams;

    @Column(name = "max_weight_grams")
    private Integer maxWeightGrams;

    @Column(name = "insurance_pct", precision = 5, scale = 2)
    private BigDecimal insurancePct;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
