package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A qué categoría o producto alcanza una promoción (tabla {@code promotion_target}).
 *
 * <p>Cada fila apunta a UNA cosa: o categoría o producto, nunca las dos. Lo impone la base de datos,
 * porque una fila con ambos no tendría un significado claro.
 */
@Entity
@Table(name = "promotion_target")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PromotionTargetEntity {

    @Id
    @GeneratedValue
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "promotion_id", nullable = false, columnDefinition = "uuid")
    private UUID promotionId;

    @Column(name = "category_id", columnDefinition = "uuid")
    private UUID categoryId;

    @Column(name = "product_id", columnDefinition = "uuid")
    private UUID productId;
}
