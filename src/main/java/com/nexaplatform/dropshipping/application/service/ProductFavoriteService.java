package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductFavoriteEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductFavoriteJpaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Favoritos (wishlist) de productos por usuario. Add es idempotente (el UNIQUE de BD evita duplicados);
 * remove es idempotente. La lista de favoritos (con datos de producto y precios) la construye el
 * {@code CatalogStorefrontReadService} a partir de los IDs que devuelve {@link #favoriteProductIds}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductFavoriteService {

    private final ProductFavoriteJpaRepository favoriteRepo;
    private final ProductRepository productRepository;

    /** Añade el producto a los favoritos del usuario (idempotente). */
    @Transactional
    public void add(UUID userId, UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw new NotFoundException("Product");
        }
        if (favoriteRepo.existsByUserIdAndProductId(userId, productId)) {
            return; // ya es favorito: no duplicamos
        }
        favoriteRepo.save(ProductFavoriteEntity.builder().userId(userId).productId(productId).build());
    }

    /** Quita el producto de los favoritos del usuario (idempotente). */
    @Transactional
    public void remove(UUID userId, UUID productId) {
        favoriteRepo.deleteByUserIdAndProductId(userId, productId);
    }

    @Transactional(readOnly = true)
    public boolean isFavorite(UUID userId, UUID productId) {
        return favoriteRepo.existsByUserIdAndProductId(userId, productId);
    }

    /** IDs de los productos favoritos del usuario, del más reciente al más antiguo. */
    @Transactional(readOnly = true)
    public List<UUID> favoriteProductIds(UUID userId) {
        return favoriteRepo.findProductIdsByUserId(userId);
    }

    @Transactional(readOnly = true)
    public long count(UUID userId) {
        return favoriteRepo.countByUserId(userId);
    }
}
