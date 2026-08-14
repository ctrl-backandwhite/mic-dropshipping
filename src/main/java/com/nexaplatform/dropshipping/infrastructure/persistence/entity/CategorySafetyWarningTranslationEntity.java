package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Texto de una advertencia de seguridad en un idioma. El art. 19.d exige que la advertencia se muestre "en
 * un lenguaje fácilmente comprensible para los consumidores", así que una advertencia sin traducir al idioma
 * del comprador no cumple: por eso el texto vive aquí y no como columna de la advertencia.
 */
@Entity
@Table(name = "category_safety_warning_translation")
@Getter
@Setter
@NoArgsConstructor
public class CategorySafetyWarningTranslationEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    /**
     * Solo lectura: la columna la escribe el {@code @JoinColumn} de {@code CategorySafetyWarningEntity}.
     * Mapearla dos veces como escribible haría que Hibernate se contradijera a sí mismo sobre quién manda.
     */
    @Column(name = "warning_id", nullable = false, insertable = false, updatable = false)
    private UUID warningId;

    @Column(nullable = false, length = 8)
    private String language;

    @Column(nullable = false, length = 1000)
    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
