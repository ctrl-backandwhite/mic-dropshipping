package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.WalletTransaction;
import com.nexaplatform.dropshipping.domain.repository.WalletTransactionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletTransactionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.WalletTransactionEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletTransactionJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link WalletTransactionRepository}
 * domain port on top of Spring Data JPA. Owns the persistence-only concern the
 * domain model abstracts away: resolving the managed {@code wallet} relation from
 * the flattened {@code walletId} on save. Ledger listing preserves the legacy
 * newest-first ordering and pagination.
 */
@Repository
@RequiredArgsConstructor
public class WalletTransactionRepositoryImpl implements WalletTransactionRepository {

    private final WalletTransactionEntityMapper walletTransactionEntityMapper;
    private final WalletTransactionJpaRepositoryAdapter walletTransactionJpaRepositoryAdapter;
    private final WalletJpaRepositoryAdapter walletJpaRepositoryAdapter;

    @Override
    public WalletTransaction save(WalletTransaction model) {
        WalletTransactionEntity entity = walletTransactionEntityMapper.toEntity(model);
        entity.setWallet(resolveWallet(model.getWalletId()));
        WalletTransactionEntity saved = walletTransactionJpaRepositoryAdapter.save(entity);
        return walletTransactionEntityMapper.toDomain(saved);
    }

    @Override
    public WalletTransaction update(WalletTransaction model) {
        return this.save(model);
    }

    @Override
    public WalletTransaction getById(UUID id) {
        return walletTransactionJpaRepositoryAdapter.findById(id)
                .map(walletTransactionEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public List<WalletTransaction> findByWalletIdOrderByCreatedAtDesc(UUID walletId, int page, int size) {
        return walletTransactionEntityMapper.toDomainList(
                walletTransactionJpaRepositoryAdapter
                        .findByWallet_IdOrderByCreatedAtDesc(walletId, PageRequest.of(page, Math.min(size, 100)))
                        .getContent());
    }

    @Override
    public long countByWalletId(UUID walletId) {
        return walletTransactionJpaRepositoryAdapter.countByWallet_Id(walletId);
    }

    @Override
    public Optional<WalletTransaction> findByIdempotencyKey(String idempotencyKey) {
        return walletTransactionJpaRepositoryAdapter.findByIdempotencyKey(idempotencyKey)
                .map(walletTransactionEntityMapper::toDomain);
    }

    @Override
    public void delete(UUID id) {
        walletTransactionJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return walletTransactionJpaRepositoryAdapter.existsById(id);
    }

    /** Resolves the owning wallet from its id, failing if it does not exist. */
    private WalletEntity resolveWallet(UUID walletId) {
        if (walletId == null) {
            return null;
        }
        return walletJpaRepositoryAdapter.findById(walletId)
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
    }
}
