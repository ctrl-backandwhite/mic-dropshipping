package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.domain.enums.UserRole;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<UserEntity, UUID> {
    Optional<UserEntity> findByEmail(String email);

    Optional<UserEntity> findByActivationCode(String code);

    boolean existsByEmail(String email);

    List<UserEntity> findByRoleOrderByCreatedAtDesc(UserRole role);
}
