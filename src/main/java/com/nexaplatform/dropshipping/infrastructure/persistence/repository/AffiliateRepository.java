package com.nexaplatform.dropshipping.infrastructure.persistence.repository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.AffiliateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;
public interface AffiliateRepository extends JpaRepository<AffiliateEntity, UUID> {
    Optional<AffiliateEntity> findByUser_Id(UUID userId);
    Optional<AffiliateEntity> findByCode(String code);
}
