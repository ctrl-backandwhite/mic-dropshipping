package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryRegionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repo de las regiones (estado/provincia) por país. */
public interface CountryRegionRepository extends JpaRepository<CountryRegionEntity, UUID> {

    List<CountryRegionEntity> findByCountryCodeIgnoreCaseAndActiveTrueOrderByPositionAscRegionNameAsc(
            String countryCode);

    /** Todas las regiones de un país (incluidas inactivas) para el admin. */
    List<CountryRegionEntity> findByCountryCodeIgnoreCaseOrderByPositionAscRegionNameAsc(String countryCode);

    Optional<CountryRegionEntity> findByCountryCodeIgnoreCaseAndRegionCodeIgnoreCase(String countryCode,
            String regionCode);
}
