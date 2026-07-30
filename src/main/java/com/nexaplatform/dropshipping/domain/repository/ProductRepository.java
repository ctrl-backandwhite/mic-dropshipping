package com.nexaplatform.dropshipping.domain.repository;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Domain repository port for {@link Product} (Catalog aggregate root). Implemented
 * by an infrastructure adapter bridging to Spring Data JPA. Distinct from the
 * legacy Spring Data interface in {@code infrastructure.persistence.repository}.
 * Re-declares every finder the {@code CatalogUseCase} needs, all returning the
 * {@link Product} domain model.
 */
public interface ProductRepository extends BaseRepository<Product, Product, UUID> {

    Optional<Product> findBySlug(String slug);

    Optional<Product> findBySourceAndExternalId(String source, String externalId);

    /** Resolves SKU/external id without knowing the source — useful for inbound store webhooks. */
    Optional<Product> findFirstByExternalId(String externalId);

    Optional<Product> findWithDetailsBySlug(String slug);

    Optional<Product> findWithDetailsById(UUID id);

    Page<Product> findByStatus(ProductStatus status, Pageable pageable);

    Page<Product> findAll(Pageable pageable);

    Page<Product> findTopByTrendScore(ProductStatus status, Pageable pageable);

    Page<Product> findByCategoryOrderByTrend(UUID categoryId, ProductStatus status, Pageable pageable);

    List<Product> findAllById(List<UUID> ids);
}
