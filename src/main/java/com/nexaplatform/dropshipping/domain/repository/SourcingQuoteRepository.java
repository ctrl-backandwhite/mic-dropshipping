package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.SourcingQuote;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link SourcingQuote}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface SourcingQuoteRepository extends BaseRepository<SourcingQuote, SourcingQuote, UUID> {

    /** Lists the quotes of a request, cheapest first. */
    List<SourcingQuote> findByRequestIdOrderByPriceUsdCentsAsc(UUID requestId);
}
