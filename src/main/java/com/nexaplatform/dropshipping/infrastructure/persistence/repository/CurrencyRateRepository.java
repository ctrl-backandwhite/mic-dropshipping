package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CurrencyRateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CurrencyRateRepository extends JpaRepository<CurrencyRateEntity, UUID> {
    List<CurrencyRateEntity> findByActiveTrueOrderByCodeAsc();
    Optional<CurrencyRateEntity> findByCodeIgnoreCase(String code);
}
