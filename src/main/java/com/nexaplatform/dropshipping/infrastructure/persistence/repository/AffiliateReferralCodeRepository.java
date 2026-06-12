package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateReferralCodeEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AffiliateReferralCodeRepository extends JpaRepository<AffiliateReferralCodeEntity, UUID> {

    Optional<AffiliateReferralCodeEntity> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    List<AffiliateReferralCodeEntity> findByAffiliateIdOrderByCreatedAtAsc(UUID affiliateId);
}
