package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateProgramConfigEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AffiliateProgramConfigRepository extends JpaRepository<AffiliateProgramConfigEntity, UUID> {

    Optional<AffiliateProgramConfigEntity> findFirstByOrderByCreatedAtAsc();
}
