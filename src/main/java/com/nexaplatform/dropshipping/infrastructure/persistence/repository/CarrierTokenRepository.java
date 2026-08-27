package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CarrierTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** El token vivo de cada transportista. Hay como mucho una fila por transportista (índice único). */
public interface CarrierTokenRepository extends JpaRepository<CarrierTokenEntity, UUID> {

    Optional<CarrierTokenEntity> findByCarrier(String carrier);
}
