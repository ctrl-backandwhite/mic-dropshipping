package com.nexaplatform.dropshipping.application.usecase;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.AnuncioBusFallidoView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.CustomsAuditView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogImageDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CatalogPriceTierDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.Category1688MappingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CategoryAttributeSchemaDtoOut;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Use-case port for the Catalog cluster (aggregate root {@link Product}). Absorbs
 * the logic that used to live in {@code CatalogService} and in the catalog
 * controllers, operating on the {@link Product} domain model and the catalog DTOs.
 * The three catalog controllers depend on this port (the partner controller no
 * longer reaches into the storefront controller).
 */
public interface CatalogUseCase {

    /* ============ Ingest (admin) ============ */

    SupplierEntity upsertSupplier(IngestSupplierRequest req);

    CategoryEntity createCategoryRejectingDuplicateSlug(IngestCategoryRequest req);

    CategoryEntity upsertCategory(IngestCategoryRequest req);

    ProductEntity upsertProduct(IngestProductRequest req);

    BigDecimal computeTrendScore(ProductEntity p);

    /* ============ Products: read ============ */

    Page<ProductSummaryView> listProducts(ProductStatus status, Pageable pageable, String language);

    /**
     * Listado del panel de admin.
     *
     * <p>Los filtros piden a gritos un record que los agrupe ({@code status}, {@code query} y
     * {@code sort} son tres cadenas seguidas que al llamar se pueden intercambiar sin error de
     * compilación), pero la firma no puede cambiar desde aquí: la implementación es la que lleva el
     * {@code @Transactional(readOnly = true)} que mantiene abierta la sesión mientras se mapean las
     * traducciones LAZY, y una firma nueva obligaría a un método puente en esta interfaz. Ese puente
     * llamaría al método anotado desde dentro del propio objetivo —sin pasar por el proxy—, así que el
     * listado se quedaría sin transacción y reventaría con LazyInitializationException. La agrupación
     * llega cuando se migren de golpe el controlador de admin y sus pruebas.
     */
    @SuppressWarnings("java:S107")
    Page<ProductSummaryView> listProductsForAdmin(String status, UUID categoryId, String query, int page, int size,
            String language, String sort, Boolean verified, BigDecimal minCost, BigDecimal maxCost, Integer minSales,
            BigDecimal minTrend);

    /** Reindexes every product into OpenSearch; returns the number indexed. */
    int reindexAllProducts();

    /** Estado de un reindexado: si hay uno en curso, si esta llamada acaba de lanzarlo y el último recuento. */
    record ReindexStatus(boolean running, boolean started, int lastIndexed) {
    }

    /**
     * Lanza el reindexado completo en SEGUNDO PLANO y responde al instante (no bloquea la petición HTTP,
     * que con miles de productos moriría por timeout del proxy/edge). Si ya había uno en curso no arranca
     * otro ({@code started=false}).
     */
    ReindexStatus startReindex();

    /** Estado actual del reindexado en background (para que el panel muestre "en curso"/"terminado"). */
    ReindexStatus reindexStatus();

    /** DROP-679: rellena el SEO (meta_title/meta_description) faltante de productos ya activos. */
    int backfillMissingSeo();

    /** Deriva los ejes/valores de variación faltantes desde las variantes (productos sin selector). */
    int backfillVariantAxes();

    /* ============ DROP-677: mapeo categorías 1688 → interna ============ */

    UUID upsertCategory1688Mapping(String external1688Id, String external1688Name, UUID categoryId);

    List<Category1688MappingDtoOut> listCategory1688Mappings();

    void deleteCategory1688Mapping(UUID id);

    /* ============ DROP-670: esquema de atributos por categoría ============ */

    List<CategoryAttributeSchemaDtoOut> listCategoryAttributeSchema(UUID categoryId);

    UUID upsertCategoryAttributeSchema(UUID categoryId, String attrKey, String label, boolean required, int position);

    void deleteCategoryAttributeSchema(UUID id);

    /** Lists a product's variants with their RAW stored price (admin manage view, no margin/currency). */
    List<VariantView> listVariantsForAdmin(UUID productId);

    /** Creates a variant on a product and reindexes it. */
    VariantView createVariant(UUID productId, AdminVariantUpsertDtoIn req);

    /** Updates an existing variant and reindexes its product. */
    VariantView updateVariant(UUID variantId, AdminVariantUpsertDtoIn req);

    /** Partial update: sets only the variant's price (en moneda canónica) sin tocar el resto. */
    VariantView updateVariantPrice(UUID variantId, BigDecimal price);

    /** Renombra la etiqueta visible de un valor de variación (p.ej. un color) y reindexa el producto. */
    void renameVariantValue(UUID valueId, String label);

    /** Elimina un valor de variación (color/estampado) y las combinaciones (variantes) que lo usan. */
    void deleteVariantValue(UUID valueId);

    /** DROP-674: fija la imagen real de un valor de variación (p.ej. la foto de un color). Vacío la elimina. */
    void setVariantValueImage(UUID valueId, String imageUrl);

    /** Fija/actualiza la traducción de un valor de variación para un idioma (valor vacío la elimina). */
    void setVariantValueTranslation(UUID valueId, String language, String value);

    /** Deletes a variant and reindexes its product. */
    void deleteVariant(UUID variantId);

    /** Adds an image (by URL) to a product's gallery. */
    ProductImageView addProductImage(UUID productId, String url, String role);

    /** Removes a product image and reindexes its product. */
    void deleteProductImage(UUID imageId);

    /** Clears the explanation video of a product (video_url, has_video, video_urls) — admin only. */
    void deleteProductVideo(UUID id);

    /** Reorders a product's gallery images to match the given id order (first becomes MAIN). */
    void reorderProductImages(UUID productId, List<UUID> imageIds);

    /** Bulk-creates products from friendly JSON rows; returns created/failed counts and errors. */
    BulkResultDtoOut bulkCreateProducts(List<BulkProductDtoIn> rows);

    /**
     * Exports products in the given 1-based inclusive range (ordered deterministically by id) as the same
     * {@link BulkProductDtoIn} JSON shape used to create them, so the result can be re-imported. Lets the
     * admin export the catalog in fixed segments (1-1000, 1001-2000, …).
     */
    List<BulkProductDtoIn> exportProducts(int from, int to, ExportFilter filtro);

    /** Una página del volcado: las filas ya mapeadas y si queda alguna más detrás. */
    record ProductExportBatch(List<BulkProductDtoIn> items, boolean hayMas) {
    }

    /**
     * Una página del volcado continuo, ordenada por id y con los hijos traídos por lote (sin N+1).
     *
     * <p>Se pagina por DESPLAZAMIENTO y no por clave. Antes iba por clave —id mayor que el último— con
     * una consulta nativa, porque así se podía filtrar solo por fecha y certificación. Al tener que
     * acotar también por categoría, texto, precio, ventas y tendencia, mantener esa consulta aparte
     * habría significado reescribir la búsqueda del panel entera en SQL nativo, con dos sitios donde
     * decidir qué entra en el catálogo. Se reutiliza la consulta de la LISTA, que ya sabe hacerlo.
     *
     * <p>Lo que se cede es el techo: el desplazamiento se degrada en páginas muy profundas. Con nueve
     * mil productos no se nota, y a cambio la exportación no puede volver a discrepar de lo que la
     * lista enseña.
     */
    ProductExportBatch exportPage(int page, int size, ExportFilter filtro);

    /** Exporta UN producto al formato de carga masiva (para editarlo como JSON y reimportar con upsert). */
    BulkProductDtoIn exportProduct(UUID id);

    /**
     * Con qué se acota una exportación: los MISMOS filtros que la lista del panel, más el rango por
     * fecha de carga.
     *
     * <p>Antes la exportación solo conocía fecha y certificación, así que filtrar la lista a treinta
     * productos y abrir «Exportar» ofrecía los nueve mil. Y es un registro, y no diez parámetros
     * sueltos, porque los tres caminos de exportación —contar, tramo y volcado continuo— tienen que
     * acotar EXACTAMENTE igual: si alguno se deja uno, los segmentos que se ofrecen no cuadran con lo
     * que después se descarga.
     */
    record ExportFilter(String status, UUID categoryId, String q, Boolean verified, BigDecimal minCost,
            BigDecimal maxCost, Integer minSales, BigDecimal minTrend, Instant createdFrom, Instant createdTo) {

        /** Sin ningún filtro: la exportación completa. */
        public static ExportFilter todo() {
            return new ExportFilter(null, null, null, null, null, null, null, null, null, null);
        }
    }

    /**
     * Total de productos (para calcular los segmentos de export), con los filtros dados. Cuenta con los
     * MISMOS que exporta, o los segmentos que se ofrecen no cuadrarían con lo que después se descarga.
     */
    long countProducts(ExportFilter filtro);

    /** Creates a single product manually from a friendly row; returns the new product id. */
    UUID createProductManual(BulkProductDtoIn req);

    /** Permanently deletes a product and its catalog children (refused if it has orders). */
    void deleteProduct(UUID id);

    /**
     * Resultado de una operación en lote: cuántas salieron bien y el motivo de cada fallo.
     *
     * <p>Los lotes NO se paran ante el primer error: un producto que no se puede borrar porque tiene
     * pedidos no debe impedir que se borren los demás de la selección.
     */
    record BulkOutcome(int succeeded, List<String> errors) {

        public int failed() {
            return errors.size();
        }
    }

    /** Borra los productos indicados, continuando ante fallos individuales. */
    BulkOutcome bulkDeleteProducts(List<UUID> ids);

    /** Cambia el estado (ACTIVE/PAUSED/ARCHIVED) de los productos indicados. */
    BulkOutcome bulkUpdateStatus(List<UUID> ids, String status);

    /** Bulk-creates categories from friendly JSON rows. */
    BulkResultDtoOut bulkCreateCategories(List<BulkCategoryDtoIn> rows);

    ProductSummaryView toSummaryView(Product product, String language);

    Product getProductModelById(UUID id);

    Product getProductModelBySlug(String slug);

    ProductDetailView getProductBySlug(String slug, String language);

    ProductDetailView getProductById(UUID id, String language);

    ProductDetailView getProductByExternal(String source, String externalId, String language);

    Page<ProductSummaryView> listBestsellers(UUID categoryId, Pageable pageable, String language);

    List<CatalogImageDtoOut> listProductImages(UUID productId);

    List<CatalogPriceTierDtoOut> listProductPriceTiers(UUID productId);

    /**
     * Productos del catálogo que no se podrían declarar en aduana, con el detalle de qué les falta.
     *
     * @param status estado a repasar ({@code ACTIVE}, {@code DRAFT}…); vacío o desconocido repasa todo
     * @param max    tope de filas devueltas; el resultado avisa si se quedaron más fuera
     */
    CustomsAuditView auditCustomsData(String status, int max);

    /* ============ Products: mutate (admin) ============ */

    void updateStatus(UUID id, String status);

    void updateStatus(UUID id, ProductStatus status);

    ProductDetailView quickEdit(UUID id, AdminProductQuickEditDtoIn req, String lang);

    /**
     * Update del recargo fijo por producto (surcharge_cny) en lote: por producto, por categoría o para
     * todo el catálogo (30-ago-2026). Devuelve cuántos productos se actualizaron.
     */
    int bulkUpdateSurcharge(java.util.List<java.util.UUID> productIds, java.util.UUID categoryId,
            BigDecimal surchargeCny);

    /**
     * Update en lote de las dos bolsas de subvención por producto: por producto, por categoría o para
     * todo el catálogo (1-sep-2026). Un importe nulo deja esa bolsa como estaba, para poder tocar una
     * sin pisar la otra. Devuelve cuántos productos se actualizaron.
     */
    int bulkUpdateSubsidy(java.util.List<java.util.UUID> productIds, java.util.UUID categoryId,
            BigDecimal shippingUserCny, BigDecimal dutyUserCny);

    /** Corrige el enlace a la ficha del proveedor (1688/Alibaba); rechaza cualquier otro dominio o esquema. */
    ProductDetailView updateSourceUrl(UUID id, String sourceUrl, String lang);

    /** Elimina un tramo de precio (price break) de un producto, identificado por su cantidad mínima. */
    void deletePriceTier(UUID productId, int minQty);

    /**
     * Fija el recargo de UN tramo y devuelve la ficha ya recalculada (23-sep-2026).
     *
     * <p>{@code surchargeCny} nulo devuelve el tramo a heredar el recargo del producto; cero es un
     * recargo de cero, que es otra cosa. El envio y el arancel no se tocan: siguen siendo uno por
     * producto porque esos si escalan con el bulto.
     */
    ProductDetailView updatePriceTierSurcharge(UUID productId, int minQty, BigDecimal surchargeCny, String lang);

    ProductDetailView duplicateProduct(UUID id, String lang);

    /**
     * Los productos cuyo anuncio al bus del catálogo se dio por perdido.
     *
     * <p>Certificar responde al instante porque el envío va diferido; el precio de eso es que un fallo
     * ya no puede devolverse en la respuesta. Esta lista es donde se ve.
     */
    List<AnuncioBusFallidoView> anunciosAlBusFallidos();

    /** Devuelve a la cola los anuncios dados por perdidos. Devuelve cuántos se reencolaron. */
    int reintentarAnunciosAlBusFallidos();
}
