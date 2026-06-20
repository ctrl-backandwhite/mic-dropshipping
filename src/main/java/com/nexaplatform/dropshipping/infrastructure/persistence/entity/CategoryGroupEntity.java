package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Grupo de categorías: una colección de categorías que comparten una regla de margen (scope
 * CATEGORY_GROUP). Permite aplicar UN solo margen a varias categorías (p. ej. todo el calzado). La
 * pertenencia vive en {@link CategoryGroupMemberEntity}.
 */
@Entity
@Table(name = "category_group")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryGroupEntity extends BaseEntity {

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 300)
    private String description;
}
