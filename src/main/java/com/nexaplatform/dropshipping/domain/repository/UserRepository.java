package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.model.User;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for the {@link User} aggregate root. Implemented by an
 * infrastructure adapter bridging to Spring Data JPA. Distinct from the legacy
 * Spring Data interface of the same simple name in
 * {@code infrastructure.persistence.repository} (different package). Re-declares
 * the finders the auth/admin use cases need on top of the base CRUD contract.
 */
public interface UserRepository extends BaseRepository<User, User, UUID> {

    /** Look up a user by its (normalized) email. */
    Optional<User> findByEmail(String email);

    /** Look up a user by its pending activation code. */
    Optional<User> findByActivationCode(String code);

    /** Whether a user with the given email already exists. */
    boolean existsByEmail(String email);

    /** All users, newest first, used by the admin listing. */
    @Override
    List<User> findAll();
}
