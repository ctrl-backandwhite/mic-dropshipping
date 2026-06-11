package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code CustomerSubscriptionRepository} domain port. */
public interface CustomerSubscriptionJpaRepositoryAdapter extends JpaRepository<CustomerSubscriptionEntity, UUID> {

    List<CustomerSubscriptionEntity> findByUserId(UUID userId);
}
