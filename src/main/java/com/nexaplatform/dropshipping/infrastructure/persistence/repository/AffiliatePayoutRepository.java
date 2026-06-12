package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliatePayoutEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AffiliatePayoutRepository extends JpaRepository<AffiliatePayoutEntity, UUID> {

    List<AffiliatePayoutEntity> findByAffiliateIdOrderByCreatedAtDesc(UUID affiliateId);

    List<AffiliatePayoutEntity> findByStatusOrderByCreatedAtDesc(String status);

    boolean existsByAffiliateIdAndStatus(UUID affiliateId, String status);
}
