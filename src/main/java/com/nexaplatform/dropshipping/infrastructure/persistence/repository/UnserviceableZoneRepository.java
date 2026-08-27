package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UnserviceableZoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Zonas excluidas por el transportista, por país. */
public interface UnserviceableZoneRepository extends JpaRepository<UnserviceableZoneEntity, UUID> {

    List<UnserviceableZoneEntity> findByCountryCodeIgnoreCase(String countryCode);
}
