package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;
import java.util.UUID;

/**
 * Free-form technical specification of a product (Material, Modelo, Peso real…) stored per
 * locale so each language can carry its own translation of both the key and the value.
 */
@Entity
@Table(name = "product_specification")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductSpecificationEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private ProductEntity product;

    @Column(nullable = false, length = 8)
    private String locale;

    @Column(name = "spec_key", nullable = false, length = 80)
    private String specKey;

    @Column(name = "spec_value", nullable = false, length = 800)
    private String specValue;

    @Column(nullable = false)
    private int position;

    @CreatedDate
    @Column(name = "created_at", updatable = false, nullable = false)
    private Instant createdAt;
}
