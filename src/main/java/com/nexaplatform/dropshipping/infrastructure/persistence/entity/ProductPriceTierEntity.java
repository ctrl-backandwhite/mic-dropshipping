package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "product_price_tier")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductPriceTierEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "variant_id")
    private ProductVariantEntity variant;

    @Column(name = "min_qty", nullable = false)
    private int minQty;

    @Column(name = "max_qty")
    private Integer maxQty;

    @Column(name = "unit_price", precision = 12, scale = 4, nullable = false)
    private BigDecimal unitPrice;

    @Column(length = 8)
    private String currency;

    /**
     * El recargo propio de ESTE tramo, en PORCENTAJE sobre el coste del proveedor (25-sep-2026).
     *
     * <p>Manda sobre el del producto, y por encima de cualquier otra regla de precio: si el tramo
     * declara el suyo se aplica ese y no se mira nada más. Nulo y cero no son lo mismo —nulo es «no
     * tengo uno propio, hereda el global»; cero es «un recargo de cero»— y confundirlos pondría a cero
     * los 3.240 tramos que hoy heredan.
     */
    @Column(name = "surcharge_pct", precision = 6, scale = 3)
    private BigDecimal surchargePct;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
