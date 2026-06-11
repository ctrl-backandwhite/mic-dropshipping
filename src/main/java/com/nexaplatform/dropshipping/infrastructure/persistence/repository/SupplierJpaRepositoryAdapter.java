package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Spring Data JPA adapter backing the {@code SupplierRepository} domain port. */
public interface SupplierJpaRepositoryAdapter extends JpaRepository<SupplierEntity, UUID> {
}
