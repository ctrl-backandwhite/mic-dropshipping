package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryTaxRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repo de las tasas de impuesto por país. */
public interface CountryTaxRateRepository extends JpaRepository<CountryTaxRateEntity, UUID> {

    Optional<CountryTaxRateEntity> findByCountryCodeIgnoreCase(String countryCode);

    List<CountryTaxRateEntity> findAllByOrderByCountryCodeAsc();
}
