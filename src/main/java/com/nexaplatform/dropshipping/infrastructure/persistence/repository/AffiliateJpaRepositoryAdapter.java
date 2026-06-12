package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code AffiliateRepository} domain port. */
public interface AffiliateJpaRepositoryAdapter extends JpaRepository<AffiliateEntity, UUID> {

    Optional<AffiliateEntity> findByUser_Id(UUID userId);

    /** Eagerly fetch the user so admin row mapping works outside the service transaction. */
    @Query("select a from AffiliateEntity a join fetch a.user order by a.earningsUsdCents desc")
    List<AffiliateEntity> findAllWithUser();
}
