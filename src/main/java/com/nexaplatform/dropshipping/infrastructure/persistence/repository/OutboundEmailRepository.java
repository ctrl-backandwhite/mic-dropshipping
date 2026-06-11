package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OutboundEmailEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboundEmailRepository extends JpaRepository<OutboundEmailEntity, UUID> {
    List<OutboundEmailEntity> findTop20ByStatusOrderByCreatedAtAsc(String status);
}
