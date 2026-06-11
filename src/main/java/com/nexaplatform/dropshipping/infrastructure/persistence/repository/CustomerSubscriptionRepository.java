package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomerSubscriptionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CustomerSubscriptionRepository extends JpaRepository<CustomerSubscriptionEntity, UUID> {
    List<CustomerSubscriptionEntity> findByUserId(UUID userId);
    Optional<CustomerSubscriptionEntity> findByStripeSubscriptionId(String stripeId);

    /** Suscripciones ACTIVE o TRIALING para el usuario, ordenadas por fin de periodo (más reciente primero). */
    @Query("SELECT s FROM CustomerSubscriptionEntity s " +
           "WHERE s.user.id = :userId AND s.status IN ('ACTIVE','TRIALING') " +
           "ORDER BY s.currentPeriodEnd DESC NULLS LAST")
    List<CustomerSubscriptionEntity> findActiveByUserId(@Param("userId") UUID userId);
}
