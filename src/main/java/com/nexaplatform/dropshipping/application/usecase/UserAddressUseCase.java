package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.domain.model.UserAddress;

import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the authenticated user's addresses; operates on the
 * {@link UserAddress} domain model. Every operation is scoped to a {@code userId}
 * to enforce ownership and to preserve the single-default-address invariant.
 */
public interface UserAddressUseCase {

    /** List the user's addresses, default first then newest. */
    List<UserAddress> findAll(UUID userId);

    /** Create a new address for the user; demotes the previous default if needed. */
    UserAddress save(UUID userId, UserAddress model);

    /** Update an address owned by the user; handles default promotion. */
    UserAddress update(UUID userId, UUID id, UserAddress model);

    /** Delete an address owned by the user. */
    void delete(UUID userId, UUID id);
}
