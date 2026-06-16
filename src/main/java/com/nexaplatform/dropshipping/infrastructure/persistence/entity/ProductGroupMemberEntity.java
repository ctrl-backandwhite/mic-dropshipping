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

/** Membership of a product in a {@link ProductGroupEntity} (composite key group_id + product_id). */
@Entity
@Table(name = "product_group_member")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ProductGroupMemberEntity {

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
        @Column(name = "product_id", columnDefinition = "uuid", nullable = false)
        private UUID productId;
    }
}
