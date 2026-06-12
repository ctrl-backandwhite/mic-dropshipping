package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code OrderRepository} domain port. */
public interface OrderJpaRepositoryAdapter extends JpaRepository<CustomerOrderEntity, UUID> {

    Optional<CustomerOrderEntity> findByOrderNumber(String orderNumber);

    List<CustomerOrderEntity> findByPartnerAppId(UUID partnerAppId);
}
