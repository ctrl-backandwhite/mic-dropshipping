package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletTransactionEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WalletTransactionRepository extends JpaRepository<WalletTransactionEntity, UUID> {
    Page<WalletTransactionEntity> findByWallet_IdOrderByCreatedAtDesc(UUID walletId, Pageable pageable);

    Optional<WalletTransactionEntity> findByIdempotencyKey(String idempotencyKey);
}
