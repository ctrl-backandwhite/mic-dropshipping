package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Advertencia de seguridad asociada a una categoría (art. 19.d del Reglamento (UE) 2023/988: la oferta en
 * línea debe incluir las advertencias "en un lenguaje fácilmente comprensible para los consumidores").
 *
 * <p>Cuelga de la categoría y no del producto porque el riesgo pertenece a la familia —baterías, piezas
 * pequeñas, elementos eléctricos, tinte textil—, no a la referencia concreta; con más de cinco mil productos
 * cargados, exigirlo uno a uno garantizaría que la mayoría se quedara sin advertencia. Se hereda hacia los
 * hijos: lo puesto en "Belleza" alcanza a "Secador de pelo".
 */
@Entity
@Table(name = "category_safety_warning")
@Getter
@Setter
@NoArgsConstructor
public class CategorySafetyWarningEntity {

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "category_id", nullable = false)
    private UUID categoryId;

    /**
     * Código estable del tipo de advertencia (p. ej. {@code CHOKING_HAZARD}). Sobrevive a los cambios de
     * redacción y permite reconocer la misma advertencia en categorías distintas para no repetirla cuando
     * una hereda de otra.
     */
    @Column(nullable = false, length = 60)
    private String code;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false)
    private boolean active;

    /**
     * {@code nullable = false} no es decorativo: sin él, al borrar la advertencia Hibernate intenta
     * desligar los hijos con un {@code UPDATE ... SET warning_id = NULL} antes del DELETE, y como la
     * columna es NOT NULL la operación acaba en violación de integridad — un 409 al borrar desde el panel.
     */
    @OneToMany(fetch = FetchType.LAZY, cascade = CascadeType.ALL, orphanRemoval = true)
    @JoinColumn(name = "warning_id", nullable = false)
    private List<CategorySafetyWarningTranslationEntity> translations = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "created_by", length = 120)
    private String createdBy;

    @Column(name = "updated_by", length = 120)
    private String updatedBy;
}
