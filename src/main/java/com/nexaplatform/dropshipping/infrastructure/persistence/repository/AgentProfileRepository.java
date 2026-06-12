package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AgentProfileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface AgentProfileRepository extends JpaRepository<AgentProfileEntity, UUID> {
    List<AgentProfileEntity> findByActiveTrueOrderBySatisfactionDesc();
}
