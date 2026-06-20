package com.nexaplatform.dropshipping.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/** Pertenencia de una categoría a un {@link CategoryGroupEntity} (clave compuesta group_id + category_id). */
@Entity
@Table(name = "category_group_member")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CategoryGroupMemberEntity {

    @EmbeddedId
    private Id id;

    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        @Column(name = "group_id", columnDefinition = "uuid", nullable = false)
        private UUID groupId;
        @Column(name = "category_id", columnDefinition = "uuid", nullable = false)
        private UUID categoryId;
    }
}
