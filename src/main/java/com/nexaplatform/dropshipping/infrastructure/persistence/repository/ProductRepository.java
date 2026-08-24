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
import java.time.Instant;
import java.util.List;
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
     * Identificadores a repasar en la auditoría aduanera, de los más nuevos a los más viejos.
     *
     * <p>Se piden solo los ids, y luego los productos en tandas: cargar el catálogo entero con sus
     * traducciones y variantes de una vez es justo lo que no se puede hacer con miles de referencias.
     * {@code status} nulo repasa todo el catálogo.
     */
    @Query("SELECT p.id FROM ProductEntity p WHERE (:status IS NULL OR p.status = :status)")
    Page<UUID> findIdsForCustomsAudit(@Param("status") ProductStatus status, Pageable pageable);

    /**
     * Productos de una tanda con sus traducciones ya cargadas.
     *
     * <p>Va aparte de {@link #findWithVariantsByIds} a propósito: traer las dos colecciones en el mismo
     * {@code JOIN FETCH} es un producto cartesiano que Hibernate rechaza (dos bolsas). Ejecutadas
     * seguidas dentro de la misma transacción, la segunda rellena las variantes sobre estas mismas
     * instancias, que es lo que necesita la comprobación.
     */
    @Query("SELECT DISTINCT p FROM ProductEntity p LEFT JOIN FETCH p.translations WHERE p.id IN :ids")
    List<ProductEntity> findWithTranslationsByIds(@Param("ids") List<UUID> ids);

    /** La otra mitad de la tanda: las variantes, que son las que llevan peso y precio propios. */
    @Query("SELECT DISTINCT p FROM ProductEntity p LEFT JOIN FETCH p.variants WHERE p.id IN :ids")
    List<ProductEntity> findWithVariantsByIds(@Param("ids") List<UUID> ids);

    /** Total publicado: la portada lo enseña sin necesidad de abrir el listado. */
    long countByStatus(ProductStatus status);

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

    /**
     * Escaparate — productos VISIBLES que tienen vídeo de explicación ({@code hasVideo = true}). Filtra en
     * BD (no trae un lote y filtra en memoria), así la sección "Productos con vídeo" del home los encuentra
     * aunque estén lejos en el catálogo. Misma visibilidad que {@link #findVisibleByStatus} (activo + imagen
     * espejada). Orden por trendScore para mostrar primero los más relevantes.
     */
    @Query("""
            SELECT p FROM ProductEntity p
            WHERE p.status = :status
              AND p.hasVideo = TRUE
              AND EXISTS (SELECT 1 FROM ProductImageEntity i WHERE i.product = p AND i.cdnUrl IS NOT NULL)
            ORDER BY p.trendScore DESC NULLS LAST, p.id ASC
            """)
    Page<ProductEntity> findVisibleWithVideo(@Param("status") ProductStatus status, Pageable pageable);

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
            ORDER BY p.trendScore DESC NULLS LAST, p.id ASC
            """)
    Page<ProductEntity> findTopByTrendScore(@Param("status") ProductStatus status, Pageable pageable);

    @Query("""
            SELECT p FROM ProductEntity p
            WHERE p.status = :status AND p.category.id = :categoryId
              AND EXISTS (SELECT 1 FROM ProductImageEntity i WHERE i.product = p AND i.cdnUrl IS NOT NULL)
            ORDER BY p.trendScore DESC NULLS LAST, p.id ASC
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
     *
     * <p><b>Precisión del texto libre</b> (buscar "botas" devolvía vestidos y blazers):
     * <ul>
     *   <li>{@code lang} — el fuzzy ({@code nx_wmatch}) SOLO se aplica al título del idioma activo. Al
     *       evaluarlo contra los 8 idiomas a la vez, "botas" (es) casaba con "botao" (pt) con similitud
     *       0.50 ≥ 0.45. El LIKE literal sí sigue cruzando idiomas: una subcadena exacta es intencional.</li>
     *   <li>{@code wide} — las descripciones solo se miran si se pide. Una falda cuya descripción dice
     *       "combina con botas" no es un resultado de "botas"; el servicio reintenta con {@code wide=true}
     *       únicamente cuando el match fuerte (título/slug/atributo/variante) no devuelve nada.</li>
     *   <li>{@code ranked} — con texto y orden "best_match", los productos que llevan el término EN EL
     *       TÍTULO van primero y el resto (atributo, variante, descripción) después. Con {@code false}
     *       todos empatan a 0 y manda el {@code Sort} del {@code Pageable} (precio, novedad…).</li>
     * </ul>
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
              AND (:verified   IS NULL OR COALESCE(p.verified, FALSE) = :verified)
              AND (CAST(:needle AS string) IS NULL
                   OR nx_norm(p.titleZh)    LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')
                   OR nx_norm(p.externalId) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')
                   OR nx_norm(p.slug)       LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')
                   OR nx_wmatch(CAST(:needle AS string), p.titleZh) = TRUE
                   OR EXISTS (SELECT 1 FROM ProductTranslationEntity t
                              WHERE t.product = p
                                AND (nx_norm(t.title) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')
                                     OR (t.language = CAST(:lang AS string)
                                         AND nx_wmatch(CAST(:needle AS string), t.title) = TRUE)))
                   OR EXISTS (SELECT 1 FROM ProductAttributeEntity a
                              WHERE a.product = p
                                AND nx_norm(a.attrValue) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%'))
                   OR EXISTS (SELECT 1 FROM VariantOptionEntity vo JOIN vo.values vv
                              WHERE vo.product = p
                                AND (nx_norm(vv.valueZh) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')
                                     OR nx_norm(vv.value) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')))
                   OR EXISTS (SELECT 1 FROM VariantValueTranslationEntity vt
                              WHERE vt.variantValue.option.product = p
                                AND nx_norm(vt.value) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%'))
                   OR (:wide = TRUE
                       AND EXISTS (SELECT 1 FROM ProductTranslationEntity t2
                                   WHERE t2.product = p
                                     AND (nx_norm(t2.shortDescription) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')
                                          OR nx_norm(t2.description)   LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')))))
            ORDER BY CASE WHEN :ranked = FALSE THEN 0
                          WHEN nx_norm(p.titleZh) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%') THEN 0
                          WHEN EXISTS (SELECT 1 FROM ProductTranslationEntity tr
                                       WHERE tr.product = p
                                         AND nx_norm(tr.title) LIKE CONCAT('%', nx_norm(CAST(:needle AS string)), '%')) THEN 0
                          ELSE 1 END ASC
            """)
    // OJO: aquí NO se añade desempate por id. Esta consulta recibe el Sort del Pageable (lo construye
    // `CatalogStorefrontReadService.sortFor`, que YA termina en id), y Spring Data CONCATENA ese Sort
    // detrás del ORDER BY de la consulta. Un `p.id ASC` escrito aquí quedaría ANTES del criterio del
    // usuario —`ORDER BY relevancia, id, basePrice`— y el id mandaría sobre el precio: ordenar por
    // «precio ascendente» dejaba de ordenar por precio. Lo detectó CatalogFlowIT.ordenPorPrecio.
    // Un parámetro por filtro es una exigencia de Spring Data: cada :nombre de la consulta se enlaza con un
    // argumento del método. Agruparlos en un record obligaría a reescribir la consulta con expresiones SpEL
    // y a tocar el binding de nulos (los CAST de arriba), que es justo lo que rompía la búsqueda en DROP-556.
    @SuppressWarnings("java:S107")
    Page<ProductEntity> searchStorefront(@Param("status") ProductStatus status, @Param("needle") String needle,
            @Param("categoryId") UUID categoryId, @Param("supplierId") UUID supplierId,
            @Param("minPrice") BigDecimal minPrice, @Param("maxPrice") BigDecimal maxPrice,
            @Param("shipFrom") String shipFrom, @Param("freeShipping") Boolean freeShipping,
            @Param("selfPickup") Boolean selfPickup, @Param("hasVideo") Boolean hasVideo,
            @Param("minRating") BigDecimal minRating, @Param("minInv") Integer minInv,
            @Param("verified") Boolean verified,
            @Param("lang") String lang, @Param("wide") boolean wide, @Param("ranked") boolean ranked,
            Pageable pageable);

    /**
     * Productos del escaparate restringidos a un conjunto de identificadores — el camino que se usa cuando
     * el texto libre lo ha resuelto OpenSearch.
     *
     * <p>El reparto de responsabilidades es deliberado: el buscador decide QUÉ casa y en qué orden de
     * relevancia (que es lo que sabe hacer bien en 8 idiomas), y esta consulta aplica lo que solo la base
     * de datos sabe — visibilidad real (publicado y con imagen espejada) y el resto de filtros. Así el
     * índice no necesita conocer reglas de negocio ni reindexarse cuando cambia un margen.
     *
     * <p>Sin paginación a propósito: el conjunto ya viene acotado por el buscador y quien llama ordena por
     * relevancia y pagina en memoria, porque ese orden no existe en SQL.
     */
    @Query("""
            SELECT p FROM ProductEntity p
            WHERE p.status = :status
              AND p.id IN :ids
              AND EXISTS (SELECT 1 FROM ProductImageEntity i WHERE i.product = p AND i.cdnUrl IS NOT NULL)
              AND (:categoryId IS NULL OR p.category.id = :categoryId)
              AND (:supplierId IS NULL OR p.supplier.id = :supplierId)
              AND (CAST(:shipFrom AS string) IS NULL OR p.shipFrom = CAST(:shipFrom AS string))
              AND (:freeShipping IS NULL OR p.freeShipping = :freeShipping)
              AND (:selfPickup IS NULL OR p.selfPickup = :selfPickup)
              AND (:hasVideo IS NULL OR p.hasVideo = :hasVideo)
              AND (:minRating IS NULL OR p.rating >= :minRating)
              AND (:minInv IS NULL OR p.inventoryCount >= :minInv)
            """)
    @SuppressWarnings("java:S107")
    List<ProductEntity> searchStorefrontByIds(@Param("status") ProductStatus status, @Param("ids") List<UUID> ids,
            @Param("categoryId") UUID categoryId, @Param("supplierId") UUID supplierId,
            @Param("shipFrom") String shipFrom, @Param("freeShipping") Boolean freeShipping,
            @Param("selfPickup") Boolean selfPickup, @Param("hasVideo") Boolean hasVideo,
            @Param("minRating") BigDecimal minRating, @Param("minInv") Integer minInv);

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
                   OR nx_norm(p.titleZh)    LIKE CONCAT('%', nx_norm(:needle), '%')
                   OR nx_norm(p.externalId) LIKE CONCAT('%', nx_norm(:needle), '%')
                   OR nx_norm(p.slug)       LIKE CONCAT('%', nx_norm(:needle), '%')
                   OR nx_wmatch(:needle, p.titleZh) = TRUE
                   OR EXISTS (SELECT 1 FROM ProductTranslationEntity t
                              WHERE t.product = p
                                AND (nx_norm(t.title) LIKE CONCAT('%', nx_norm(:needle), '%')
                                     OR (t.language = CAST(:lang AS string)
                                         AND nx_wmatch(:needle, t.title) = TRUE)))
                   OR EXISTS (SELECT 1 FROM ProductAttributeEntity a
                              WHERE a.product = p
                                AND nx_norm(a.attrValue) LIKE CONCAT('%', nx_norm(:needle), '%'))
                   OR EXISTS (SELECT 1 FROM VariantOptionEntity vo JOIN vo.values vv
                              WHERE vo.product = p
                                AND (nx_norm(vv.valueZh) LIKE CONCAT('%', nx_norm(:needle), '%')
                                     OR nx_norm(vv.value) LIKE CONCAT('%', nx_norm(:needle), '%')))
                   OR EXISTS (SELECT 1 FROM VariantValueTranslationEntity vt
                              WHERE vt.variantValue.option.product = p
                                AND nx_norm(vt.value) LIKE CONCAT('%', nx_norm(:needle), '%'))
                   OR (:wide = TRUE
                       AND EXISTS (SELECT 1 FROM ProductTranslationEntity t2
                                   WHERE t2.product = p
                                     AND (nx_norm(t2.shortDescription) LIKE CONCAT('%', nx_norm(:needle), '%')
                                          OR nx_norm(t2.description)   LIKE CONCAT('%', nx_norm(:needle), '%')))))
            """)
    Page<ProductEntity> searchAdmin(@Param("status") ProductStatus status, @Param("categoryId") UUID categoryId,
            @Param("needle") String needle, @Param("verified") Boolean verified, @Param("lang") String lang,
            @Param("wide") boolean wide, Pageable pageable);

    /** IDs (distintos) de categorías con productos del estado dado ingeridos desde {@code since} — campaña de novedades. */
    @Query("""
            SELECT DISTINCT p.category.id FROM ProductEntity p
             WHERE p.status = :status AND p.category IS NOT NULL AND p.ingestedAt >= :since
            """)
    List<UUID> findCategoryIdsWithProductsIngestedSince(@Param("status") ProductStatus status,
            @Param("since") Instant since);

    /**
     * Las ternas aduaneras distintas del catálogo —partida, material y uso—, las más pobladas primero.
     *
     * <p>Es la entrada de la siembra de {@code customs_declaration_group}: una fila aquí es un grupo de
     * declaración candidato. Se agrupa por los valores <b>en crudo</b> y se normaliza después en Java
     * (ver {@link CustomsTernaRow}), así que la misma terna puede venir en varias filas con distinta
     * grafía; el orden por cuenta descendente hace que la grafía mayoritaria sea la que dé el borrador.
     */
    @Query("""
            SELECT new com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsTernaRow(
                       p.hsCode, p.customsMaterial, p.customsUsage, COUNT(p))
            FROM ProductEntity p
            WHERE p.hsCode IS NOT NULL AND p.hsCode <> ''
            GROUP BY p.hsCode, p.customsMaterial, p.customsUsage
            ORDER BY COUNT(p) DESC
            """)
    List<CustomsTernaRow> customsTernas();

    /**
     * Los productos que comparten terna aduanera con un grupo de declaración: los que se declaran con su
     * misma descripción y por tanto <b>no abren línea nueva</b> en la aduana.
     *
     * <p>Es el filtro «ver los que no suman arancel». Devuelve solo identificadores porque la visibilidad
     * real del escaparate —publicado y con imagen espejada— y el resto de filtros los aplica después
     * {@link #searchStorefrontByIds}, que es el mismo camino por el que entran los resultados del buscador.
     *
     * <p>El orden es el que manda cuando el usuario no ha pedido otro, y termina en {@code id}: sin
     * desempate, dos productos con la misma tendencia y las mismas ventas pueden salir repetidos en una
     * página e inalcanzables en la siguiente.
     *
     * <p>La normalización tiene que coincidir con {@code CustomsDeclarationGroupService.normalizeKeyPart}.
     * {@code TRIM} y {@code UPPER} cubren lo que hay hoy en el catálogo (ni un solo valor con espacios
     * dobles ni con bordes sin recortar); el colapso de espacios interiores no se puede expresar en JPQL,
     * así que un valor futuro con dos espacios seguidos quedaría fuera del filtro — se vería el producto en
     * el catálogo general, nunca una promesa falsa.
     */
    @Query("""
            SELECT p.id FROM ProductEntity p
            WHERE p.status = :status
              AND p.hsCode LIKE CONCAT(CAST(:hs6 AS string), '%')
              AND UPPER(TRIM(COALESCE(p.customsMaterial, ''))) = CAST(:material AS string)
              AND UPPER(TRIM(COALESCE(p.customsUsage, ''))) = CAST(:usageCode AS string)
              AND (CAST(:origin AS string) IS NULL
                   OR UPPER(TRIM(COALESCE(p.countryOfOrigin, ''))) = CAST(:origin AS string))
            ORDER BY p.trendScore DESC, p.monthlySales DESC, p.id ASC
            """)
    List<UUID> idsForCustomsTerna(@Param("status") ProductStatus status, @Param("hs6") String hs6,
            @Param("material") String material, @Param("usageCode") String usageCode,
            @Param("origin") String origin);
}
