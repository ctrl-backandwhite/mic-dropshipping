package com.nexaplatform.dropshipping.infrastructure.persistence.repository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SourcingRequestEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public interface SourcingRequestRepository extends JpaRepository<SourcingRequestEntity, UUID> {
    List<SourcingRequestEntity> findByUser_IdOrderByCreatedAtDesc(UUID userId);
    long countByUser_IdAndCreatedAtAfter(UUID userId, Instant from);
}
