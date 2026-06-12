package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Spring Data JPA adapter backing the {@code WalletRepository} domain port. */
public interface WalletJpaRepositoryAdapter extends JpaRepository<WalletEntity, UUID> {

    Optional<WalletEntity> findByUser_Id(UUID userId);
}
