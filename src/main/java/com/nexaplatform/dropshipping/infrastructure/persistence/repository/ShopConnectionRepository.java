package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShopConnectionRepository extends JpaRepository<ShopConnectionEntity, UUID> {
    List<ShopConnectionEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);
}
