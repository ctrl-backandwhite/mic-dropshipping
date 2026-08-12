package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SavedCartItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SavedCartItemJpaRepository extends JpaRepository<SavedCartItemEntity, UUID> {

    List<SavedCartItemEntity> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /**
     * Busca la línea guardada de una variante concreta. Spring Data traduce un {@code variantId} nulo a
     * {@code variant_id IS NULL}, así que sirve tanto para producto con variante como sin ella.
     */
    Optional<SavedCartItemEntity> findByUserIdAndProductIdAndVariantId(UUID userId, UUID productId,
            UUID variantId);

    @Modifying
    void deleteByUserIdAndProductIdAndVariantId(UUID userId, UUID productId, UUID variantId);

    long countByUserId(UUID userId);
}
