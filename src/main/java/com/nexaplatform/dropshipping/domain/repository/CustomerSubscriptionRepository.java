package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.CustomerSubscription;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link CustomerSubscription} (the Billing aggregate
 * root). Implemented by an infrastructure adapter bridging to Spring Data JPA.
 * Distinct from the legacy Spring Data interface of the same simple name in
 * {@code infrastructure.persistence.repository} (different package), which other
 * out-of-cluster consumers keep using.
 */
public interface CustomerSubscriptionRepository
        extends
            BaseRepository<CustomerSubscription, CustomerSubscription, UUID> {

    /** All subscriptions owned by the given user (storefront listing). */
    List<CustomerSubscription> findByUserId(UUID userId);
}
