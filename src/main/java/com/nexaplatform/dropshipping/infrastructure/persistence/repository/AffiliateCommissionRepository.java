package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateCommissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AffiliateCommissionRepository extends JpaRepository<AffiliateCommissionEntity, UUID> {

    Optional<AffiliateCommissionEntity> findByConversionId(UUID conversionId);

    List<AffiliateCommissionEntity> findByAffiliateIdOrderByCreatedAtDesc(UUID affiliateId);

    List<AffiliateCommissionEntity> findByStatus(String status);

    List<AffiliateCommissionEntity> findByAffiliateIdAndStatus(UUID affiliateId, String status);
}
