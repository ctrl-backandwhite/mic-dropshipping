package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PriceRuleRepository extends JpaRepository<PriceRuleEntity, UUID> {
    /** Active rules only — used by MarginService to resolve which rule applies. */
    List<PriceRuleEntity> findByActiveTrueOrderByPositionAsc();
}
