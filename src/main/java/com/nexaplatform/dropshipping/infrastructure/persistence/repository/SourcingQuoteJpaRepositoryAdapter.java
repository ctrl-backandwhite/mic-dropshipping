package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingQuoteEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code SourcingQuoteRepository} domain port. */
public interface SourcingQuoteJpaRepositoryAdapter extends JpaRepository<SourcingQuoteEntity, UUID> {

    List<SourcingQuoteEntity> findByRequest_IdOrderByPriceUsdCentsAsc(UUID requestId);
}
