package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShippingRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ShippingRateRepository extends JpaRepository<ShippingRateEntity, UUID> {
    List<ShippingRateEntity> findBySupplier_IdAndCountryCodeAndActiveTrue(UUID supplierId, String countryCode);

    List<ShippingRateEntity> findBySupplier_IdAndActiveTrue(UUID supplierId);
}
