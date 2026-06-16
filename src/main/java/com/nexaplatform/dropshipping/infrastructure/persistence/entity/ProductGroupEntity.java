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
 * A product group: an arbitrary collection of products sharing a common margin rule (price_rule scope
 * PRODUCT_GROUP). Membership lives in {@link ProductGroupMemberEntity}. A product may belong to many groups.
 */
@Entity
@Table(name = "product_group")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProductGroupEntity extends BaseEntity {

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 400)
    private String description;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
