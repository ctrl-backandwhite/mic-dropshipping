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

import java.time.Instant;
import java.util.UUID;

/**
 * Visita de un usuario a la ficha de un producto. Hay UNA fila por (usuario, producto): volver a abrir la
 * misma ficha no inserta otra, actualiza {@code viewedAt} y suma un punto a {@code viewCount}. Así el
 * historial no se llena del mismo producto repetido por recargar la página.
 */
@Entity
@Table(name = "product_view", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "product_id" }))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductViewEntity extends BaseEntity {

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Column(name = "product_id", nullable = false, columnDefinition = "uuid")
    private UUID productId;

    /** Momento de la ÚLTIMA visita: es lo que ordena el historial y lo que decide si entra en el correo. */
    @Column(name = "viewed_at", nullable = false)
    private Instant viewedAt;

    /** Cuántas veces ha vuelto a la ficha. Es lo único que se perdería al consolidar las visitas. */
    @Column(name = "view_count", nullable = false)
    @Builder.Default
    private int viewCount = 1;
}
