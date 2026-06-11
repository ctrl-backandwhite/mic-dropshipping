package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "product_warehouse_stock",
       uniqueConstraints = @UniqueConstraint(columnNames = { "product_id", "warehouse_id" }))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductWarehouseStockEntity extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false)
    private WarehouseEntity warehouse;

    @Column(nullable = false)
    @Builder.Default private int stock = 0;
}
