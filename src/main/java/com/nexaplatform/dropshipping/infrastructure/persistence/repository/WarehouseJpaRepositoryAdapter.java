package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WarehouseEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code WarehouseRepository} domain port. */
public interface WarehouseJpaRepositoryAdapter extends JpaRepository<WarehouseEntity, UUID> {

    List<WarehouseEntity> findByActiveTrueOrderByCountryAsc();
}
