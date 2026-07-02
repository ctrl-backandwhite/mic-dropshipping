package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Favorito (wishlist) de un producto por usuario. Tabla de unión simple con UUID de usuario y producto;
 * un usuario no puede tener el mismo producto dos veces (UNIQUE user_id, product_id).
 */
@Entity
@Table(name = "product_favorite", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "product_id" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductFavoriteEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    private UUID productId;
}
