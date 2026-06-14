package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * DROP-670: definición de un atributo esperado por una categoría (esquema dinámico). Sirve para
 * sugerir los atributos en el editor de productos y para validar la importación.
 */
@Entity
@Table(name = "category_attribute_schema")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryAttributeSchemaEntity extends BaseEntity {

    @ManyToOne(optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;

    @Column(name = "attr_key", nullable = false, length = 60)
    private String attrKey;

    @Column(length = 120)
    private String label;

    @Column(nullable = false)
    private boolean required;

    @Column(nullable = false)
    private int position;
}
