package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Wallet;
import com.nexaplatform.dropshipping.domain.repository.WalletRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.WalletEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link WalletRepository} domain port on
 * top of Spring Data JPA. Owns the persistence-only concern the domain model
 * abstracts away: resolving the managed {@code user} relation from the flattened
 * {@code userId} on save/update. The legacy Spring Data {@code UserRepository} is
 * reused as a read-only collaborator to fetch the managed user.
 */
@Repository
@RequiredArgsConstructor
public class WalletRepositoryImpl implements WalletRepository {

    private final WalletEntityMapper walletEntityMapper;
    private final WalletJpaRepositoryAdapter walletJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public Wallet save(Wallet model) {
        WalletEntity entity = resolveEntity(model);
        applyModel(entity, model);
        WalletEntity saved = walletJpaRepositoryAdapter.save(entity);
        return walletEntityMapper.toDomain(saved);
    }

    @Override
    public List<Wallet> findAll() {
        return walletEntityMapper.toDomainList(walletJpaRepositoryAdapter.findAll());
    }

    @Override
    public Optional<Wallet> findByUserId(UUID userId) {
        return walletJpaRepositoryAdapter.findByUser_Id(userId).map(walletEntityMapper::toDomain);
    }

    @Override
    public Wallet update(Wallet model) {
        return this.save(model);
    }

    @Override
    public Wallet getById(UUID id) {
        return walletJpaRepositoryAdapter.findById(id).map(walletEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        walletJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return walletJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private WalletEntity resolveEntity(Wallet model) {
        if (model.getId() != null) {
            return walletJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Wallet not found"));
        }
        return new WalletEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the managed user relation. */
    private void applyModel(WalletEntity entity, Wallet model) {
        entity.setUser(resolveUser(model.getUserId()));
        entity.setBalanceUsdCents(model.getBalanceUsdCents());
        entity.setHoldUsdCents(model.getHoldUsdCents());
        entity.setCurrencyDefault(model.getCurrencyDefault());
        entity.setStatus(model.getStatus());
    }

    /** Resolves the owning user from its id, failing if it does not exist. */
    private UserEntity resolveUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }
}
