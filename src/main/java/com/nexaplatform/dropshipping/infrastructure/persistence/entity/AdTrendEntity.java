package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ad_trend")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AdTrendEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(nullable = false, length = 20) private String source;
    @Column(nullable = false, length = 300) private String headline;
    @Column(name = "product_slug", length = 220) private String productSlug;
    @Column private Long impressions;
    @Column private Long engagement;
    @Column(precision = 6, scale = 3) private BigDecimal score;
    @Column(length = 2) private String region;

    @Column(name = "captured_at", nullable = false)
    @Builder.Default private Instant capturedAt = Instant.now();
}
