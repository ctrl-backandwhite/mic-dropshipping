package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShippingZoneEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShippingZoneRepository extends JpaRepository<ShippingZoneEntity, UUID> {
    List<ShippingZoneEntity> findBySupplier_IdAndActiveTrueOrderByCountryCodeAsc(UUID supplierId);
    boolean existsBySupplier_IdAndCountryCodeAndActiveTrue(UUID supplierId, String countryCode);
}
