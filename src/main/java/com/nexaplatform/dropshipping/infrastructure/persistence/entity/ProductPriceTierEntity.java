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
     * El recargo fijo de ESTE tramo, en la moneda del proveedor. Nulo = usa el del producto.
     *
     * <p>Nulo y cero no son lo mismo: cero es «este tramo no lleva recargo», nulo es «no tiene uno
     * propio». Esa distincion es la que permite que los tramos ya cargados sigan cobrando lo mismo.
     */
    @Column(name = "surcharge_cny", precision = 12, scale = 4)
    private BigDecimal surchargeCny;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
}
