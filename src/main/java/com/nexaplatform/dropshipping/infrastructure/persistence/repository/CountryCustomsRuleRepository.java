package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CountryCustomsRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data repo de las reglas de despacho aduanero por país. */
public interface CountryCustomsRuleRepository extends JpaRepository<CountryCustomsRuleEntity, UUID> {

    Optional<CountryCustomsRuleEntity> findByCountryCodeIgnoreCase(String countryCode);

    List<CountryCustomsRuleEntity> findAllByOrderByCountryCodeAsc();
}
