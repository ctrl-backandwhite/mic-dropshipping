package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTagEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ProductTagRepository extends JpaRepository<ProductTagEntity, UUID> {
    List<ProductTagEntity> findByProduct_Id(UUID productId);

    @Query("SELECT DISTINCT t.tag FROM ProductTagEntity t ORDER BY t.tag")
    List<String> findDistinctTags();

    @Query("SELECT t.product.id FROM ProductTagEntity t WHERE LOWER(t.tag) = LOWER(:tag)")
    List<UUID> findProductIdsByTag(String tag);
}
