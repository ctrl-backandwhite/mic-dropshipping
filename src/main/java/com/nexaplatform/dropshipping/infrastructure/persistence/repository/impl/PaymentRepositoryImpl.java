package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.Payment;
import com.nexaplatform.dropshipping.domain.repository.PaymentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PaymentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.WalletEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.PaymentEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PaymentJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.WalletJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link PaymentRepository} domain port
 * on top of Spring Data JPA. Owns the persistence-only concern the domain model
 * abstracts away: resolving the managed {@code user} and {@code wallet} relations
 * from the flattened {@code userId}/{@code walletId} on save/update ({@code orderId}
 * is a plain column). All read finders preserve the legacy newest-first ordering.
 */
@Repository
@RequiredArgsConstructor
public class PaymentRepositoryImpl implements PaymentRepository {

    private final PaymentEntityMapper paymentEntityMapper;
    private final PaymentJpaRepositoryAdapter paymentJpaRepositoryAdapter;
    private final UserRepository userRepository;
    private final WalletJpaRepositoryAdapter walletJpaRepositoryAdapter;

    @Override
    public Payment save(Payment model) {
        PaymentEntity entity = resolveEntity(model);
        applyModel(entity, model);
        PaymentEntity saved = paymentJpaRepositoryAdapter.save(entity);
        return paymentEntityMapper.toDomain(saved);
    }

    @Override
    public List<Payment> findAll() {
        return paymentEntityMapper.toDomainList(paymentJpaRepositoryAdapter.findAll());
    }

    @Override
    public Payment update(Payment model) {
        return this.save(model);
    }

    @Override
    public Payment getById(UUID id) {
        return paymentJpaRepositoryAdapter.findById(id).map(paymentEntityMapper::toDomain).orElse(null);
    }

    @Override
    public Optional<Payment> findById(UUID id) {
        return paymentJpaRepositoryAdapter.findById(id).map(paymentEntityMapper::toDomain);
    }

    @Override
    public Optional<Payment> findByIdempotencyKey(String idempotencyKey) {
        return paymentJpaRepositoryAdapter.findByIdempotencyKey(idempotencyKey).map(paymentEntityMapper::toDomain);
    }

    @Override
    public Optional<Payment> findByProviderAndProviderRef(String provider, String providerRef) {
        return paymentJpaRepositoryAdapter.findByProviderAndProviderRef(provider, providerRef)
                .map(paymentEntityMapper::toDomain);
    }

    @Override
    public List<Payment> findByUserIdOrderByCreatedAtDesc(UUID userId) {
        return paymentEntityMapper.toDomainList(paymentJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Override
    public List<Payment> findByOrderIdOrderByCreatedAtDesc(UUID orderId) {
        return paymentEntityMapper.toDomainList(paymentJpaRepositoryAdapter.findByOrderIdOrderByCreatedAtDesc(orderId));
    }

    @Override
    public void delete(UUID id) {
        paymentJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return paymentJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private PaymentEntity resolveEntity(Payment model) {
        if (model.getId() != null) {
            return paymentJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Payment not found"));
        }
        return new PaymentEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the managed relations. */
    private void applyModel(PaymentEntity entity, Payment model) {
        // Relaciones gestionadas resueltas vía lookups de repositorio.
        entity.setUser(resolveUser(model.getUserId()));
        entity.setWallet(resolveWallet(model.getWalletId()));

        // Campos escalares vía MapStruct. purpose y providerResponse se manejan aparte porque
        // requieren null-handling condicional / converter JSON, por eso se ignoran en updateEntity.
        if (model.getPurpose() != null) {
            entity.setPurpose(model.getPurpose());
        }
        paymentEntityMapper.updateEntity(entity, model);
        entity.setProviderResponse(model.getProviderResponse());
    }

    /** Resolves the paying user from its id, failing if it does not exist. */
    private UserEntity resolveUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    /** Resolves the related wallet from its id, failing if it does not exist. */
    private WalletEntity resolveWallet(UUID walletId) {
        if (walletId == null) {
            return null;
        }
        return walletJpaRepositoryAdapter.findById(walletId)
                .orElseThrow(() -> new NotFoundException("Wallet not found"));
    }
}
