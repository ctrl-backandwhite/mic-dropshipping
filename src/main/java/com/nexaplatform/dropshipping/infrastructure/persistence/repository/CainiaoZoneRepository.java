package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CainiaoZoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Acceso a la cobertura de países/zonas de Cainiao (tarifas + ETA + países habilitados). */
public interface CainiaoZoneRepository extends JpaRepository<CainiaoZoneEntity, UUID> {

    Optional<CainiaoZoneEntity> findByCountryCodeIgnoreCase(String countryCode);

    List<CainiaoZoneEntity> findByEnabledTrueOrderByCountryNameAsc();

    List<CainiaoZoneEntity> findAllByOrderByCountryNameAsc();
}
