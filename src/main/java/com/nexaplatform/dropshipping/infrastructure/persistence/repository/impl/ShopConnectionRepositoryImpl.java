package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.ShopConnection;
import com.nexaplatform.dropshipping.domain.repository.ShopConnectionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.UserEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ShopConnectionEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link ShopConnectionRepository} domain
 * port on top of Spring Data JPA. Owns the persistence-only concerns the domain
 * model abstracts away: resolving the {@code user} relation from {@code userId}
 * and preserving the metadata map across saves. {@code findByUserId} preserves the
 * legacy admin-list ordering (newest first).
 */
@Repository
@RequiredArgsConstructor
public class ShopConnectionRepositoryImpl implements ShopConnectionRepository {

    private final ShopConnectionEntityMapper shopConnectionEntityMapper;
    private final ShopConnectionJpaRepositoryAdapter shopConnectionJpaRepositoryAdapter;
    private final UserRepository userRepository;

    @Override
    public ShopConnection save(ShopConnection model) {
        ShopConnectionEntity entity = resolveEntity(model);
        applyModel(entity, model);
        ShopConnectionEntity saved = shopConnectionJpaRepositoryAdapter.save(entity);
        return shopConnectionEntityMapper.toDomain(saved);
    }

    @Override
    public List<ShopConnection> findByUserId(UUID userId) {
        return shopConnectionEntityMapper
                .toDomainList(shopConnectionJpaRepositoryAdapter.findByUser_IdOrderByCreatedAtDesc(userId));
    }

    @Override
    public ShopConnection update(ShopConnection model) {
        return this.save(model);
    }

    @Override
    public ShopConnection getById(UUID id) {
        return shopConnectionJpaRepositoryAdapter.findById(id).map(shopConnectionEntityMapper::toDomain).orElse(null);
    }

    @Override
    public void delete(UUID id) {
        shopConnectionJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return shopConnectionJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private ShopConnectionEntity resolveEntity(ShopConnection model) {
        if (model.getId() != null) {
            return shopConnectionJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Shop"));
        }
        return new ShopConnectionEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving the owning user. */
    private void applyModel(ShopConnectionEntity entity, ShopConnection model) {
        // Campos escalares (incluido metadata) vía MapStruct; la relación gestionada user se
        // resuelve abajo porque requiere un lookup de repositorio.
        shopConnectionEntityMapper.updateEntity(entity, model);
        if (entity.getUser() == null) {
            entity.setUser(resolveUser(model.getUserId()));
        }
    }

    /** Resolves the owning user from its id, failing if it does not exist. */
    private UserEntity resolveUser(UUID userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElseThrow();
    }
}
