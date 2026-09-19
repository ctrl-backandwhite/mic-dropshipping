package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import com.nexaplatform.dropshipping.domain.enums.ReviewSource;
import jakarta.persistence.*;
import lombok.*;

/** DROP-445: reseña de un producto. */
@Entity
@Table(name = "product_review")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductReviewEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private UserEntity user;

    @Column(name = "author_name", length = 120)
    private String authorName;

    @Column(name = "author_country", length = 8)
    private String authorCountry;

    @Column(nullable = false)
    private short rating;

    @Column(length = 160)
    private String title;

    @Column(columnDefinition = "text")
    private String body;

    /** Comma-separated tags (quality, shipping, value, size, color, …). */
    @Column(length = 500)
    private String tags;

    @Column(name = "helpful_count", nullable = false)
    @Builder.Default
    private int helpfulCount = 0;

    @Column(name = "verified_purchase", nullable = false)
    @Builder.Default
    private boolean verifiedPurchase = false;

    @Column(nullable = false)
    @Builder.Default
    private boolean approved = true;

    /**
     * De dónde sale la reseña. Por defecto SUPPLIER: si alguien añade un camino nuevo y olvida fijarlo,
     * la reseña se presenta como importada, que es el lado seguro. Lo contrario —dar por propia una
     * reseña ajena— es justo lo que la normativa de consumo sanciona.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 16, nullable = false)
    @Builder.Default
    private ReviewSource source = ReviewSource.SUPPLIER;

    @Column(length = 8)
    private String language;
}
