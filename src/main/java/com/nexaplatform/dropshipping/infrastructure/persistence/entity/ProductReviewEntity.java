package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

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
}
