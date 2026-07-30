package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data JPA adapter backing the {@code ProductRepository} domain port.
 * Carries the legacy derived finders and {@code @Query} definitions copied from
 * the legacy {@code ProductRepository} so the use case keeps identical SQL/index
 * coverage (storefront search, trend listings, detail entity-graphs).
 */
public interface ProductJpaRepositoryAdapter extends JpaRepository<ProductEntity, UUID> {

    Optional<ProductEntity> findBySlug(String slug);

    Optional<ProductEntity> findBySourceAndExternalId(String source, String externalId);

    /** Resolución de SKU/external id sin saber la fuente — útil para inbound webhooks de tiendas. */
    Optional<ProductEntity> findFirstByExternalId(String externalId);

    @EntityGraph(attributePaths = {"supplier", "category"})
    Optional<ProductEntity> findWithDetailsBySlug(String slug);

    @EntityGraph(attributePaths = {"supplier", "category"})
    Optional<ProductEntity> findWithDetailsById(UUID id);

    Page<ProductEntity> findByStatus(ProductStatus status, Pageable pageable);

    // Filtro de escaparate: solo productos con al menos UNA imagen ya espejada a nuestro storage
    // (cdn_url no nulo). Así garantizamos que lo que se lista SIEMPRE renderiza una imagen y se
    // ocultan los productos sin imagen utilizable, sin borrarlos: el admin los sigue viendo y, en
    // cuanto se les espeje una imagen, reaparecen solos en el escaparate.
    @Query("""
            SELECT p FROM ProductEntity p
            WHERE p.status = :status
              AND EXISTS (SELECT 1 FROM ProductImageEntity i WHERE i.product = p AND i.cdnUrl IS NOT NULL)
            ORDER BY p.trendScore DESC NULLS LAST
            """)
    Page<ProductEntity> findTopByTrendScore(@Param("status") ProductStatus status, Pageable pageable);

    @Query("""
            SELECT p FROM ProductEntity p
            WHERE p.status = :status AND p.category.id = :categoryId
              AND EXISTS (SELECT 1 FROM ProductImageEntity i WHERE i.product = p AND i.cdnUrl IS NOT NULL)
            ORDER BY p.trendScore DESC NULLS LAST
            """)
    Page<ProductEntity> findByCategoryOrderByTrend(@Param("categoryId") UUID categoryId,
            @Param("status") ProductStatus status, Pageable pageable);

    // DROP-556: CAST(:needle AS string) y CAST(:shipFrom AS string) son
    // necesarios — Hibernate JPA, al hacer el binding de un parámetro String null
    // contra PostgreSQL, envía `setNull(BYTES)` por defecto y la query falla con
    // "operator does not exist: text ~~ bytea". El CAST fuerza el tipo varchar.
    @Query("""
            SELECT p FROM ProductEntity p
            WHERE p.status = :status
              AND EXISTS (SELECT 1 FROM ProductImageEntity i WHERE i.product = p AND i.cdnUrl IS NOT NULL)
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:supplierId IS NULL OR p.supplier.id = :supplierId)
              AND (:minPrice   IS NULL OR p.basePrice >= :minPrice)
              AND (:maxPrice   IS NULL OR p.basePrice <= :maxPrice)
              AND (CAST(:shipFrom AS string) IS NULL OR p.shipFrom = CAST(:shipFrom AS string))
              AND (:freeShipping IS NULL OR p.freeShipping = :freeShipping)
              AND (:selfPickup IS NULL OR p.selfPickup = :selfPickup)
              AND (:hasVideo   IS NULL OR p.hasVideo = :hasVideo)
              AND (:minRating  IS NULL OR p.rating >= :minRating)
              AND (:minInv     IS NULL OR p.inventoryCount >= :minInv)
              AND (CAST(:needle AS string) IS NULL
                   OR LOWER(p.titleZh) LIKE CONCAT('%', CAST(:needle AS string), '%')
                   OR LOWER(p.externalId) LIKE CONCAT('%', CAST(:needle AS string), '%')
                   OR LOWER(p.slug) LIKE CONCAT('%', CAST(:needle AS string), '%')
                   OR EXISTS (SELECT 1 FROM ProductTranslationEntity t
                              WHERE t.product = p
                                AND (LOWER(t.title) LIKE CONCAT('%', CAST(:needle AS string), '%')
                                     OR LOWER(t.shortDescription) LIKE CONCAT('%', CAST(:needle AS string), '%')
                                     OR LOWER(t.description) LIKE CONCAT('%', CAST(:needle AS string), '%')))
                   OR EXISTS (SELECT 1 FROM ProductAttributeEntity a
                              WHERE a.product = p
                                AND (LOWER(a.attrValue) LIKE CONCAT('%', CAST(:needle AS string), '%')
                                     OR LOWER(a.attrKey) LIKE CONCAT('%', CAST(:needle AS string), '%'))))
            """)
    // Los filtros NO se pueden agrupar en un record (java:S107): Spring Data enlaza un @Param por
    // parámetro declarado y con él conoce el tipo Java de cada uno. Pasarlos dentro de un objeto
    // obligaría a expresiones SpEL (:#{#f.needle}), que se evalúan en ejecución y pierden ese tipo: un
    // filtro a null volvería a viajar sin tipo y PostgreSQL rompería la consulta con
    // "operator does not exist: text ~~ bytea", que es justo lo que evitan los CAST de arriba.
    // El agrupado en record sí existe aguas arriba, en ProductListFilters (api/mapper).
    @SuppressWarnings("java:S107")
    Page<ProductEntity> searchStorefront(@Param("status") ProductStatus status, @Param("needle") String needle,
            @Param("categoryId") UUID categoryId, @Param("supplierId") UUID supplierId,
            @Param("minPrice") BigDecimal minPrice, @Param("maxPrice") BigDecimal maxPrice,
            @Param("shipFrom") String shipFrom, @Param("freeShipping") Boolean freeShipping,
            @Param("selfPickup") Boolean selfPickup, @Param("hasVideo") Boolean hasVideo,
            @Param("minRating") BigDecimal minRating, @Param("minInv") Integer minInv, Pageable pageable);
}
