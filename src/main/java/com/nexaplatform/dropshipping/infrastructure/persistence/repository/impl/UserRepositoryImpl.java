package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.UserEntityMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link UserRepository} domain port on
 * top of Spring Data JPA. Bridges to the legacy Spring Data interface of the
 * same simple name (referenced by its fully-qualified name to avoid the clash).
 * For existing ids it loads the managed entity first and copies the mutable
 * fields onto it, so JPA auditing and untouched columns are preserved (mirrors
 * {@code CategoryRepositoryImpl}). {@code findAll} preserves the admin-list
 * ordering (newest first).
 */
@Repository
@RequiredArgsConstructor
public class UserRepositoryImpl implements UserRepository {

    private final UserEntityMapper userEntityMapper;
    private final com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository userJpaRepository;

    @Override
    public User save(User model) {
        UserEntity entity = resolveEntity(model);
        applyModel(entity, model);
        UserEntity saved = userJpaRepository.save(entity);
        return userEntityMapper.toDomain(saved);
    }

    @Override
    public User update(User model) {
        return this.save(model);
    }

    @Override
    public List<User> findAll() {
        return userJpaRepository.findAll().stream()
                .sorted(Comparator.comparing(UserEntity::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(userEntityMapper::toDomain).toList();
    }

    @Override
    public User getById(UUID id) {
        return userJpaRepository.findById(id).map(userEntityMapper::toDomain).orElse(null);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return userJpaRepository.findByEmail(email).map(userEntityMapper::toDomain);
    }

    @Override
    public Optional<User> findByActivationCode(String code) {
        return userJpaRepository.findByActivationCode(code).map(userEntityMapper::toDomain);
    }

    @Override
    public boolean existsByEmail(String email) {
        return userJpaRepository.existsByEmail(email);
    }

    @Override
    public boolean existsById(UUID id) {
        return userJpaRepository.existsById(id);
    }

    @Override
    public void delete(UUID id) {
        userJpaRepository.deleteById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private UserEntity resolveEntity(User model) {
        if (model.getId() != null) {
            return userJpaRepository.findById(model.getId()).orElseThrow(() -> new NotFoundException("User not found"));
        }
        return new UserEntity();
    }

    /** Copies the mutable model fields onto the managed entity (audit untouched). */
    private void applyModel(UserEntity entity, User model) {
        entity.setEmail(model.getEmail());
        entity.setPasswordHash(model.getPasswordHash());
        entity.setRole(model.getRole());
        entity.setActive(model.isActive());
        entity.setActivationCode(model.getActivationCode());
        entity.setActivationCodeExpiresAt(model.getActivationCodeExpiresAt());
        entity.setFailedLoginCount(model.getFailedLoginCount());
        entity.setLockedUntil(model.getLockedUntil());
        entity.setLastLogin(model.getLastLogin());
        entity.setDisplayName(model.getDisplayName());
        entity.setCompanyName(model.getCompanyName());
        entity.setCountry(model.getCountry());
        entity.setPhone(model.getPhone());
        entity.setAvatarUrl(model.getAvatarUrl());
        entity.setLanguage(model.getLanguage());
    }
}
