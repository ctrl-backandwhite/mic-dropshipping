package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "shop_product_listing",
       uniqueConstraints = @UniqueConstraint(columnNames = { "user_shop_connection_id", "product_id" }))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShopProductListingEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_shop_connection_id", nullable = false)
    private ShopConnectionEntity shopConnection;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @Column(name = "remote_product_id", length = 180) private String remoteProductId;
    @Column(nullable = false, length = 20)
    @Builder.Default private String status = "DRAFT";
    @Column(name = "last_pushed_at") private Instant lastPushedAt;
}
