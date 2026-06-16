package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductGroupMemberEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Membership of products in groups (composite key). Powers the pricing lookup and the admin UI. */
public interface ProductGroupMemberRepository
        extends JpaRepository<ProductGroupMemberEntity, ProductGroupMemberEntity.Id> {

    /** Group ids a product belongs to — used by MarginService to resolve PRODUCT_GROUP rules. */
    @Query("SELECT m.id.groupId FROM ProductGroupMemberEntity m WHERE m.id.productId = :productId")
    List<UUID> findGroupIdsByProductId(@Param("productId") UUID productId);

    /** Product ids in a group (admin members listing). */
    @Query("SELECT m.id.productId FROM ProductGroupMemberEntity m WHERE m.id.groupId = :groupId")
    List<UUID> findProductIdsByGroupId(@Param("groupId") UUID groupId);

    long countByIdGroupId(UUID groupId);

    boolean existsByIdGroupIdAndIdProductId(UUID groupId, UUID productId);

    @Modifying
    @Transactional
    void deleteByIdGroupIdAndIdProductId(UUID groupId, UUID productId);

    @Modifying
    @Transactional
    void deleteByIdGroupId(UUID groupId);
}
