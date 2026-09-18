package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OdmProjectEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OdmProjectRepository extends JpaRepository<OdmProjectEntity, UUID> {
    List<OdmProjectEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);

    List<OdmProjectEntity> findByStatusOrderByCreatedAtDesc(String status);
}
