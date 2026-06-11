package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.JwkKeyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface JwkKeyRepository extends JpaRepository<JwkKeyEntity, UUID> {
    List<JwkKeyEntity> findAllByActiveTrueOrderByCreatedAtDesc();
    List<JwkKeyEntity> findAllByOrderByCreatedAtDesc();
}
