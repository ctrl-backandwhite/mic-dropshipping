package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.ShopProductListing;
import com.nexaplatform.dropshipping.domain.repository.ShopProductListingRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ShopProductListingEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopProductListingJpaRepositoryAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Infrastructure adapter implementing the {@link ShopProductListingRepository}
 * domain port on top of Spring Data JPA. Owns the persistence-only concerns the
 * domain model abstracts away: resolving the {@code shopConnection} and
 * {@code product} relations from their ids while preserving the legacy
 * upsert-by-(shop, product) semantics.
 */
@Repository
@RequiredArgsConstructor
public class ShopProductListingRepositoryImpl implements ShopProductListingRepository {

    private final ShopProductListingEntityMapper shopProductListingEntityMapper;
    private final ShopProductListingJpaRepositoryAdapter shopProductListingJpaRepositoryAdapter;
    private final ShopConnectionJpaRepositoryAdapter shopConnectionJpaRepositoryAdapter;
    private final ProductRepository productRepository;

    @Override
    public ShopProductListing save(ShopProductListing model) {
        ShopProductListingEntity entity = resolveEntity(model);
        applyModel(entity, model);
        ShopProductListingEntity saved = shopProductListingJpaRepositoryAdapter.save(entity);
        return shopProductListingEntityMapper.toDomain(saved);
    }

    @Override
    public List<ShopProductListing> findByShopConnectionId(UUID shopConnectionId) {
        return shopProductListingEntityMapper.toDomainList(
                shopProductListingJpaRepositoryAdapter.findByShopConnection_Id(shopConnectionId));
    }

    @Override
    public int countByShopConnectionId(UUID shopConnectionId) {
        return shopProductListingJpaRepositoryAdapter.findByShopConnection_Id(shopConnectionId).size();
    }

    @Override
    public Optional<ShopProductListing> findByShopConnectionIdAndProductId(UUID shopConnectionId, UUID productId) {
        return shopProductListingJpaRepositoryAdapter
                .findByShopConnection_IdAndProduct_Id(shopConnectionId, productId)
                .map(shopProductListingEntityMapper::toDomain);
    }

    @Override
    public ShopProductListing update(ShopProductListing model) {
        return this.save(model);
    }

    @Override
    public ShopProductListing getById(UUID id) {
        return shopProductListingJpaRepositoryAdapter.findById(id)
                .map(shopProductListingEntityMapper::toDomain)
                .orElse(null);
    }

    @Override
    public void delete(UUID id) {
        shopProductListingJpaRepositoryAdapter.deleteById(id);
    }

    @Override
    public boolean existsById(UUID id) {
        return shopProductListingJpaRepositoryAdapter.existsById(id);
    }

    /** Loads the managed entity for an existing id, or starts a fresh one for inserts. */
    private ShopProductListingEntity resolveEntity(ShopProductListing model) {
        if (model.getId() != null) {
            return shopProductListingJpaRepositoryAdapter.findById(model.getId())
                    .orElseThrow(() -> new NotFoundException("Listing"));
        }
        return new ShopProductListingEntity();
    }

    /** Applies the mutable model fields onto the entity, resolving shop and product relations. */
    private void applyModel(ShopProductListingEntity entity, ShopProductListing model) {
        if (entity.getShopConnection() == null) {
            entity.setShopConnection(resolveShop(model.getShopConnectionId()));
        }
        if (entity.getProduct() == null) {
            entity.setProduct(resolveProduct(model.getProductId()));
        }
        entity.setRemoteProductId(model.getRemoteProductId());
        entity.setStatus(model.getStatus());
        entity.setLastPushedAt(model.getLastPushedAt());
    }

    /** Resolves the owning shop connection from its id, failing if it does not exist. */
    private ShopConnectionEntity resolveShop(UUID shopConnectionId) {
        if (shopConnectionId == null) {
            return null;
        }
        return shopConnectionJpaRepositoryAdapter.findById(shopConnectionId)
                .orElseThrow(() -> new NotFoundException("Shop"));
    }

    /** Resolves the listed product from its id, failing if it does not exist. */
    private ProductEntity resolveProduct(UUID productId) {
        if (productId == null) {
            return null;
        }
        return productRepository.findById(productId)
                .orElseThrow(() -> new NotFoundException("Product"));
    }
}
