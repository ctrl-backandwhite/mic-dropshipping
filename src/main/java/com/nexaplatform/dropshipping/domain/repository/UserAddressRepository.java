package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.UserAddress;

import java.util.List;
import java.util.UUID;

/**
 * Domain repository port for {@link UserAddress}. Implemented by an infrastructure
 * adapter bridging to Spring Data JPA. Distinct from the legacy Spring Data
 * interface in {@code infrastructure.persistence.repository}. Adds the
 * user-scoped listing used by the "my addresses" feature.
 */
public interface UserAddressRepository extends BaseRepository<UserAddress, UserAddress, UUID> {

    /** List a user's addresses, default first then newest. */
    List<UserAddress> findByUserId(UUID userId);
}
