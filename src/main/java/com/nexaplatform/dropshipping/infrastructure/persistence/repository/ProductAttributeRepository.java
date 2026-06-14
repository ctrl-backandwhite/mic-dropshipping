package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface ProductAttributeRepository extends JpaRepository<ProductAttributeEntity, UUID> {
    List<ProductAttributeEntity> findByProduct_Id(UUID productId);

    // DROP-672: las facetas/filtrado operan SOLO sobre atributos neutrales (locale IS NULL); las
    // variantes localizadas son únicamente para mostrar y no fragmentan el índice de facetas.
    @Query("SELECT DISTINCT a.attrKey FROM ProductAttributeEntity a WHERE a.locale IS NULL ORDER BY a.attrKey")
    List<String> findDistinctKeys();

    @Query("SELECT DISTINCT a.attrValue FROM ProductAttributeEntity a WHERE a.attrKey = :key AND a.locale IS NULL ORDER BY a.attrValue")
    List<String> findDistinctValuesByKey(String key);

    @Query("SELECT a.product.id FROM ProductAttributeEntity a WHERE a.attrKey = :key AND a.attrValue = :value AND a.locale IS NULL")
    List<UUID> findProductIdsByKv(String key, String value);
}
