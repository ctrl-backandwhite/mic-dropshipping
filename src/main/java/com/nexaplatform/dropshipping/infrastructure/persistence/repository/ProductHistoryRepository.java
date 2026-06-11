package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface ProductHistoryRepository extends JpaRepository<ProductHistoryEntity, UUID> {
    List<ProductHistoryEntity> findByProduct_IdAndSnapshotDateGreaterThanEqualOrderBySnapshotDateAsc(
            UUID productId, LocalDate from);
}
