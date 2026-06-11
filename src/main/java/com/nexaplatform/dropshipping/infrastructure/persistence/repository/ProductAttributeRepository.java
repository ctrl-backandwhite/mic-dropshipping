package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ProductAttributeRepository extends JpaRepository<ProductAttributeEntity, UUID> {
    List<ProductAttributeEntity> findByProduct_Id(UUID productId);

    @Query("SELECT DISTINCT a.attrKey FROM ProductAttributeEntity a ORDER BY a.attrKey")
    List<String> findDistinctKeys();

    @Query("SELECT DISTINCT a.attrValue FROM ProductAttributeEntity a WHERE a.attrKey = :key ORDER BY a.attrValue")
    List<String> findDistinctValuesByKey(String key);

    @Query("SELECT a.product.id FROM ProductAttributeEntity a WHERE a.attrKey = :key AND a.attrValue = :value")
    List<UUID> findProductIdsByKv(String key, String value);
}
