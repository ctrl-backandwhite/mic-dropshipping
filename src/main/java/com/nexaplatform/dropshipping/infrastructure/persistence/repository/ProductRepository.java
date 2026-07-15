package com.nexaplatform.dropshipping.infrastructure.persistence.repository;

import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<ProductEntity, UUID> {

    Optional<ProductEntity> findBySlug(String slug);

    /** Number of products already attached to a category — used by the demo catalog filler. */
    long countByCategoryId(UUID categoryId);

    /** Number of products from a given supplier — used by the storefront supplier view count. */
    long countBySupplierId(UUID supplierId);

    Optional<ProductEntity> findBySourceAndExternalId(String source, String externalId);

    /** Resolución de SKU/external id sin saber la fuente — útil para inbound webhooks de tiendas. */
    Optional<ProductEntity> findFirstByExternalId(String externalId);

    @EntityGraph(attributePaths = {"supplier", "category"})
    Optional<ProductEntity> findWithDetailsBySlug(String slug);

    @EntityGraph(attributePaths = {"supplier", "category"})
    Optional<ProductEntity> findWithDetailsById(UUID id);

    Page<ProductEntity> findByStatus(ProductStatus status, Pageable pageable);

    /**
     * Variante de escaparate de {@link #findByStatus}: solo productos con al menos UNA imagen ya
     * espejada a nuestro storage (cdn_url no nulo). Garantiza que lo listado siempre renderiza una
     * imagen y oculta los productos sin imagen utilizable, sin borrarlos (el admin los sigue viendo;
     * reaparecen solos en cuanto se les espeje una imagen). Usar esta en rutas públicas; dejar
     * {@link #findByStatus} para conteos/admin.
     */
    @Query("""
            SELECT p FROM ProductEntity p
            WHERE p.status = :status
              AND EXISTS (SELECT 1 FROM ProductImageEntity i WHERE i.product = p AND i.cdnUrl IS NOT NULL)
            """)
    Page<ProductEntity> findVisibleByStatus(@Param("status") ProductStatus status, Pageable pageable);

    /** Admin product list filtered by category (any status). */
    Page<ProductEntity> findByCategoryId(UUID categoryId, Pageable pageable);

    /** Admin product list filtered by category and status. */
    Page<ProductEntity> findByCategoryIdAndStatus(UUID categoryId, ProductStatus status, Pageable pageable);

    // Escaparate: EXISTS sobre una imagen con cdn_url no nulo → solo se listan productos cuya imagen
    // renderiza de verdad (espejada a nuestro storage). Misma lógica que findVisibleByStatus.
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

    /**
     * Listado del storefront con filtros aplicados en SQL — sustituye al stream
     * Java del controlador que se cargaba 2000 productos en memoria.
     * <p>
     * Todos los parámetros aceptan {@code null} y se omiten en la query mediante
     * el patrón {@code :p IS NULL OR p.col = :p}. Los índices compuestos creados
     * en {@code schema-v29-perf-composite-indexes.sql} cubren las combinaciones
     * más golpeadas (status+category+trend, status+supplier, status+ship_from).
     *
     * <p>El sort no se expresa aquí porque Spring lo añade desde el {@code Pageable}.
     */
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
    Page<ProductEntity> searchStorefront(@Param("status") ProductStatus status, @Param("needle") String needle,
            @Param("categoryId") UUID categoryId, @Param("supplierId") UUID supplierId,
            @Param("minPrice") java.math.BigDecimal minPrice, @Param("maxPrice") java.math.BigDecimal maxPrice,
            @Param("shipFrom") String shipFrom, @Param("freeShipping") Boolean freeShipping,
            @Param("selfPickup") Boolean selfPickup, @Param("hasVideo") Boolean hasVideo,
            @Param("minRating") java.math.BigDecimal minRating, @Param("minInv") Integer minInv, Pageable pageable);

    /**
     * Admin free-text search across the WHOLE catalogue and ALL languages, mirroring the storefront
     * {@link #searchStorefront} matching rules but WITHOUT the {@code ACTIVE}-only / has-image constraints
     * (the admin list must surface every product, in any status, with or without an image). Matches the
     * needle against the Chinese title, the supplier external id, the slug and every translation
     * ({@link com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity}:
     * title / short description / description — i.e. ES/EN/PT/…). Optional {@code status}/{@code categoryId}
     * filters still apply. The {@code needle} is expected already lower-cased and non-null (callers only use
     * this method when the free-text box has content).
     */
    @Query("""
            SELECT p FROM ProductEntity p
            WHERE (:status IS NULL OR p.status = :status)
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:verified IS NULL OR p.verified = :verified)
              AND (:needle = ''
                   OR LOWER(p.titleZh) LIKE CONCAT('%', :needle, '%')
                   OR LOWER(p.externalId) LIKE CONCAT('%', :needle, '%')
                   OR LOWER(p.slug) LIKE CONCAT('%', :needle, '%')
                   OR EXISTS (SELECT 1 FROM ProductTranslationEntity t
                              WHERE t.product = p
                                AND (LOWER(t.title) LIKE CONCAT('%', :needle, '%')
                                     OR LOWER(t.shortDescription) LIKE CONCAT('%', :needle, '%')
                                     OR LOWER(t.description) LIKE CONCAT('%', :needle, '%'))))
            """)
    Page<ProductEntity> searchAdmin(@Param("status") ProductStatus status, @Param("categoryId") UUID categoryId,
            @Param("needle") String needle, @Param("verified") Boolean verified, Pageable pageable);
}
