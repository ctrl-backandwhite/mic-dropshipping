package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductFavoriteEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA repository for product favorites (wishlist). */
public interface ProductFavoriteJpaRepository extends JpaRepository<ProductFavoriteEntity, UUID> {

    boolean existsByUserIdAndProductId(UUID userId, UUID productId);

    long countByUserId(UUID userId);

    @Modifying
    void deleteByUserIdAndProductId(UUID userId, UUID productId);

    /** IDs de los productos favoritos del usuario, del más reciente al más antiguo. */
    @Query("SELECT f.productId FROM ProductFavoriteEntity f WHERE f.userId = :userId ORDER BY f.createdAt DESC")
    List<UUID> findProductIdsByUserId(@Param("userId") UUID userId);
}
