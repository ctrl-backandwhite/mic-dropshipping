package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "intelligence_alert")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntelligenceAlertEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(length = 200)
    private String keyword;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private CategoryEntity category;

    @Column(nullable = false, length = 20)
    @Builder.Default
    private String channel = "EMAIL";

    @Column(name = "threshold_score", precision = 6, scale = 3)
    private BigDecimal thresholdScore;
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
