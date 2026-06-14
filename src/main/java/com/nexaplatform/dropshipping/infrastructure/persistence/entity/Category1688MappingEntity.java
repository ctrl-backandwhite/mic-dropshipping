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
 * DROP-677: correspondencia entre una categoría de 1688 (id y, opcionalmente, nombre de origen) y la
 * categoría interna del catálogo. Permite resolver automáticamente la categoría del producto al
 * importar sin que el operador indique el slug interno.
 */
@Entity
@Table(name = "category_1688_mapping")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Category1688MappingEntity extends BaseEntity {

    @Column(name = "external_1688_id", nullable = false, length = 120)
    private String external1688Id;

    @Column(name = "external_1688_name", length = 300)
    private String external1688Name;

    @ManyToOne(optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private CategoryEntity category;
}
