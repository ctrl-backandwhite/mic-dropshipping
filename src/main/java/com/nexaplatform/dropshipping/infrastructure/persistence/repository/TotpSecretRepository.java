package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.TotpSecretEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface TotpSecretRepository extends JpaRepository<TotpSecretEntity, UUID> {
}
