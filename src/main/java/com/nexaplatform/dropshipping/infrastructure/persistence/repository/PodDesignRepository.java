package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PodDesignEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface PodDesignRepository extends JpaRepository<PodDesignEntity, UUID> {
    List<PodDesignEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);
}
