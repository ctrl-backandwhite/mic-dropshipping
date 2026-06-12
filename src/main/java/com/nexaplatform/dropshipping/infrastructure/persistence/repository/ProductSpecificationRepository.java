package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProductSpecificationRepository extends JpaRepository<ProductSpecificationEntity, UUID> {
    List<ProductSpecificationEntity> findByProduct_IdOrderByPositionAsc(UUID productId);

    List<ProductSpecificationEntity> findByProduct_IdAndLocaleOrderByPositionAsc(UUID productId, String locale);
}
