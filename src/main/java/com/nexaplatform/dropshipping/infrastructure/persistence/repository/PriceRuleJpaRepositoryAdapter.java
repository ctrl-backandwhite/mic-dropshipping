package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PriceRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code PriceRuleRepository} domain port. */
public interface PriceRuleJpaRepositoryAdapter extends JpaRepository<PriceRuleEntity, UUID> {

    List<PriceRuleEntity> findByActiveTrueOrderByPositionAsc();

    /** All rules (active and inactive) ordered by position — for the admin list (toggle visibility). */
    List<PriceRuleEntity> findAllByOrderByPositionAsc();
}
