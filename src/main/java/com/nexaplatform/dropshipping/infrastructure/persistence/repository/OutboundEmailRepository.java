package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OutboundEmailEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboundEmailRepository extends JpaRepository<OutboundEmailEntity, UUID> {
    List<OutboundEmailEntity> findTop20ByStatusOrderByCreatedAtAsc(String status);

    /** Deduplicación de campañas: ¿ya se encoló este template a esta dirección desde {@code createdAt}? */
    boolean existsByToAddressAndTemplateAndCreatedAtGreaterThanEqual(String toAddress, String template,
            Instant createdAt);
}
