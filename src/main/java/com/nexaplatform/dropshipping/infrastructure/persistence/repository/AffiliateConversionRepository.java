package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateConversionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AffiliateConversionRepository extends JpaRepository<AffiliateConversionEntity, UUID> {

    Optional<AffiliateConversionEntity> findByOrderId(UUID orderId);

    boolean existsByOrderId(UUID orderId);

    List<AffiliateConversionEntity> findByAffiliateIdOrderByCreatedAtDesc(UUID affiliateId);
}
