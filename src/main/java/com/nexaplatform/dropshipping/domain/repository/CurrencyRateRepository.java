package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.CurrencyRate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link CurrencyRate}. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface in {@code infrastructure.persistence.repository}.
 */
public interface CurrencyRateRepository extends BaseRepository<CurrencyRate, CurrencyRate, UUID> {

    /** Lists active rates ordered by code (legacy storefront/admin contract). */
    List<CurrencyRate> findActive();

    /** Looks up a rate by its ISO code, case-insensitively. */
    Optional<CurrencyRate> findByCode(String code);
}
