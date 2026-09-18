package com.nexaplatform.dropshipping.api.controller;

import com.nexaplatform.dropshipping.api.AdminCatalogApi;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.AnuncioBusFallidoView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.CustomsAuditView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductDetailView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductImageView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.UpdateProductStatusRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.VariantView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.in.AddProductImageDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.ReorderProductImagesDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminSubsidyBulkDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminSurchargeBulkDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductSourceUrlDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.AdminVariantUpsertDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.Category1688MappingDtoOut;
import com.nexaplatform.dropshipping.api.dto.out.CategoryAttributeSchemaDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.ErrorMessages;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService;
import lombok.RequiredArgsConstructor;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin Catalog controller. Pure implementation of {@link AdminCatalogApi}: every
 * endpoint delegates to the {@link CatalogUseCase}; no business logic, no manual
 * mapping. Routes and status codes are inherited from the API interface.
 */
@RestController
@RequestMapping("/api/admin/catalog")
@RequiredArgsConstructor
public class AdminCatalogController implements AdminCatalogApi {

    private static final int MAX_BATCH = 1000;
    /** Topes anti-DoS de la importación NDJSON (cuerpo crudo por streaming). */
    private static final long MAX_IMPORT_BYTES = 100L * 1024 * 1024; // 100 MB
    private static final int MAX_IMPORT_LINE_CHARS = 2_000_000;       // ~2 MB por línea
    private static final long MAX_IMPORT_RECORDS = 1_000_000L;

    private final CatalogUseCase catalogUseCase;
    private final ObjectMapper objectMapper;
    private final ImageMirrorService imageMirrorService;

    @Override
    public ResponseEntity<UUID> upsertSupplier(IngestSupplierRequest req) {
        return ResponseEntity.ok(catalogUseCase.upsertSupplier(req).getId());
    }

    @Override
    public ResponseEntity<UUID> upsertCategory(IngestCategoryRequest req) {
        return ResponseEntity.ok(catalogUseCase.createCategoryRejectingDuplicateSlug(req).getId());
    }

    @Override
    public ResponseEntity<UUID> upsertProduct(IngestProductRequest req) {
        return ResponseEntity.ok(catalogUseCase.upsertProduct(req).getId());
    }

    @Override
    public PageResponse<ProductSummaryView> list(String status, UUID categoryId, String q, int page, int size,
            String lang, String sort, Boolean verified, BigDecimal minCost, BigDecimal maxCost,
            Integer minSales, BigDecimal minTrend) {
        return PageResponse.from(catalogUseCase.listProductsForAdmin(status, categoryId, q, page, size, lang, sort,
                verified, minCost, maxCost, minSales, minTrend));
    }

    @Override
    public CustomsAuditView customsGaps(String status, int max) {
        return catalogUseCase.auditCustomsData(status, max);
    }

    @Override
    public ProductDetailView detail(UUID id, String lang) {
        return catalogUseCase.getProductById(id, lang);
    }

    @Override
    public ResponseEntity<Void> updateStatus(UUID id, UpdateProductStatusRequest req) {
        catalogUseCase.updateStatus(id, req.status());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ProductDetailView quickEdit(UUID id, AdminProductQuickEditDtoIn req, String lang) {
        return catalogUseCase.quickEdit(id, req, lang);
    }

    @Override
    public ResponseEntity<Map<String, Object>> bulkUpdateSurcharge(AdminSurchargeBulkDtoIn req) {
        // Recargo fijo por producto (30-ago-2026): por producto, por categoría o para todo el catálogo.
        int actualizados = catalogUseCase.bulkUpdateSurcharge(req.getProductIds(), req.getCategoryId(),
                req.getSurchargeCny());
        return ResponseEntity.ok(Map.of("updated", actualizados));
    }

    @Override
    public ResponseEntity<Map<String, Object>> bulkUpdateSubsidy(AdminSubsidyBulkDtoIn req) {
        // Bolsas de subvención (1-sep-2026): por producto, por categoría o para todo el catálogo.
        int actualizados = catalogUseCase.bulkUpdateSubsidy(req.getProductIds(), req.getCategoryId(),
                req.getShippingUserCny(), req.getDutyUserCny());
        return ResponseEntity.ok(Map.of("updated", actualizados));
    }

    @Override
    public ProductDetailView updateSourceUrl(UUID id, AdminProductSourceUrlDtoIn req, String lang) {
        return catalogUseCase.updateSourceUrl(id, req.getSourceUrl(), lang);
    }

    @Override
    public ProductDetailView duplicate(UUID id, String lang) {
        return catalogUseCase.duplicateProduct(id, lang);
    }

    @Override
    public ResponseEntity<Map<String, Object>> reindex() {
        // Reindexado en SEGUNDO PLANO: responde al instante (con miles de productos, hacerlo síncrono
        // superaba el timeout del proxy/edge y el admin veía "No se pudo reindexar").
        CatalogUseCase.ReindexStatus s = catalogUseCase.startReindex();
        return ResponseEntity.accepted().body(Map.of(
                "started", s.started(), "running", s.running(), "indexed", s.lastIndexed()));
    }

    /**
     * Devuelve a la cola un lote de imágenes ya guardadas para que pasen por el compresor.
     *
     * <p>Reindexar NO hace esto, aunque lo parezca: arrastra el espejado, pero el barrido solo mira las
     * imágenes pendientes, y estas constan como hechas. Sin este empujón, la compresión solo alcanzaría a
     * las fotos que se carguen a partir de ahora.
     *
     * <p>Va por lotes porque reprocesar es volver a descargar del origen y recomprimir: casi setenta y
     * tres mil de golpe saturarían la red, el almacén y la CPU a la vez, y mientras hay gente comprando.
     */
    @Override
    public ResponseEntity<Map<String, Object>> comprimirHistoricoDeImagenes(int limite) {
        ImageMirrorService.ReencoladoParaComprimir r = imageMirrorService.reencolarParaComprimir(limite);
        return ResponseEntity.accepted().body(Map.of(
                "reencoladas", r.reencoladas(), "pendientes", r.pendientes()));
    }

    @Override
    public ResponseEntity<Map<String, Object>> estadoDeCompresionDeImagenes() {
        ImageMirrorService.EstadoDeCompresion e = imageMirrorService.estadoDeCompresion();
        return ResponseEntity.ok(Map.of("pendientes", e.pendientes(), "enCola", e.enCola()));
    }

    @Override
    public ResponseEntity<Map<String, Object>> reindexStatus() {
        CatalogUseCase.ReindexStatus s = catalogUseCase.reindexStatus();
        return ResponseEntity.ok(Map.of("running", s.running(), "indexed", s.lastIndexed()));
    }

    @Override
    public ResponseEntity<List<VariantView>> listVariants(UUID productId) {
        return ResponseEntity.ok(catalogUseCase.listVariantsForAdmin(productId));
    }

    @Override
    public ResponseEntity<VariantView> createVariant(UUID productId, AdminVariantUpsertDtoIn req) {
        return ResponseEntity.ok(catalogUseCase.createVariant(productId, req));
    }

    @Override
    public ResponseEntity<VariantView> updateVariant(UUID id, AdminVariantUpsertDtoIn req) {
        return ResponseEntity.ok(catalogUseCase.updateVariant(id, req));
    }

    /** Edición inline del precio por variante (moneda canónica), sin tocar el resto de campos. */
    @PutMapping("/variants/{id}/price")
    public ResponseEntity<VariantView> updateVariantPrice(@PathVariable UUID id,
            @RequestBody Map<String, BigDecimal> body) {
        return ResponseEntity.ok(catalogUseCase.updateVariantPrice(id, body.get("price")));
    }

    /** Renombra la etiqueta visible de un valor de variación (p.ej. un color/estampado). */
    @PutMapping("/variant-values/{id}/label")
    public ResponseEntity<Void> renameVariantValue(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        catalogUseCase.renameVariantValue(id, body.get("value"));
        return ResponseEntity.noContent().build();
    }

    /** DROP-674: fija la imagen real de un valor de variación (p.ej. la foto de un color). */
    @PutMapping("/variant-values/{id}/image")
    public ResponseEntity<Void> setVariantValueImage(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        catalogUseCase.setVariantValueImage(id, body.get("imageUrl"));
        return ResponseEntity.noContent().build();
    }

    /** Elimina un valor de variación (p.ej. un color/estampado) y todas las combinaciones que lo usan. */
    @DeleteMapping("/variant-values/{id}")
    public ResponseEntity<Void> deleteVariantValue(@PathVariable UUID id) {
        catalogUseCase.deleteVariantValue(id);
        return ResponseEntity.noContent().build();
    }

    /** Traducción por idioma de un valor de variación (color/talla) — valor vacío la elimina. */
    @PutMapping("/variant-values/{id}/translation")
    public ResponseEntity<Void> setVariantValueTranslation(@PathVariable UUID id,
            @RequestBody Map<String, String> body) {
        catalogUseCase.setVariantValueTranslation(id, body.get("language"), body.get("value"));
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deleteVariant(UUID id) {
        catalogUseCase.deleteVariant(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<ProductImageView> addProductImage(
            UUID productId, AddProductImageDtoIn req) {
        return new ResponseEntity<>(catalogUseCase.addProductImage(productId, req.getUrl(), req.getRole()),
                HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<Void> deleteProductImage(UUID imageId) {
        catalogUseCase.deleteProductImage(imageId);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deleteProductVideo(UUID id) {
        catalogUseCase.deleteProductVideo(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> reorderProductImages(UUID productId, ReorderProductImagesDtoIn req) {
        catalogUseCase.reorderProductImages(productId, req.getImageIds());
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<UUID> createProduct(BulkProductDtoIn req) {
        return ResponseEntity.ok(catalogUseCase.createProductManual(req));
    }

    @Override
    public ResponseEntity<Void> deleteProduct(UUID id) {
        catalogUseCase.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Void> deletePriceTier(UUID id, int minQty) {
        catalogUseCase.deletePriceTier(id, minQty);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<Map<String, Object>> bulkDeleteProducts(List<UUID> ids) {
        CatalogUseCase.BulkOutcome result = catalogUseCase.bulkDeleteProducts(ids);
        return ResponseEntity.ok(Map.of("deleted", result.succeeded(), "failed", result.failed(),
                "errors", result.errors()));
    }

    /** Bulk publish/pause/archive the selected products (sets status: ACTIVE/PAUSED/ARCHIVED). */
    @PutMapping("/products/bulk-status")
    public ResponseEntity<Map<String, Object>> bulkProductStatus(@RequestBody BulkStatusRequest req) {
        CatalogUseCase.BulkOutcome result = catalogUseCase.bulkUpdateStatus(req.ids(), req.status());
        return ResponseEntity.ok(Map.of("succeeded", result.succeeded(), "failed", result.failed(),
                "errors", result.errors()));
    }

    public record BulkStatusRequest(List<UUID> ids, String status) {
    }

    @Override
    public ResponseEntity<List<AnuncioBusFallidoView>> anunciosAlBusFallidos() {
        return ResponseEntity.ok(catalogUseCase.anunciosAlBusFallidos());
    }

    @Override
    public ResponseEntity<Map<String, Integer>> reintentarAnunciosAlBusFallidos() {
        return ResponseEntity.ok(Map.of("reencolados", catalogUseCase.reintentarAnunciosAlBusFallidos()));
    }

    @Override
    public ResponseEntity<BulkResultDtoOut> bulkProducts(List<BulkProductDtoIn> rows) {
        return ResponseEntity.ok(catalogUseCase.bulkCreateProducts(rows));
    }

    @Override
    public ResponseEntity<List<BulkProductDtoIn>> exportProducts(int from, int to, String createdFrom, String createdTo, Boolean verified, String status, UUID categoryId,
            String q, BigDecimal minCost, BigDecimal maxCost, Integer minSales, BigDecimal minTrend) {
        return ResponseEntity.ok(catalogUseCase.exportProducts(from, to,
                filtroDeExportacion(createdFrom, createdTo, verified, status, categoryId, q, minCost, maxCost,
                        minSales, minTrend)));
    }

    /**
     * Arma el filtro con el que se cuenta, se exporta por tramos y se vuelca: los TRES caminos tienen que
     * acotar exactamente igual, o los segmentos que el panel ofrece no cuadran con lo que se descarga.
     */
    private static CatalogUseCase.ExportFilter filtroDeExportacion(String createdFrom, String createdTo,
            Boolean verified, String status, UUID categoryId, String q, BigDecimal minCost, BigDecimal maxCost,
            Integer minSales, BigDecimal minTrend) {
        return new CatalogUseCase.ExportFilter(status, categoryId, q, verified, minCost, maxCost, minSales,
                minTrend, startOfDay(createdFrom), endOfDayExclusive(createdTo));
    }

    @Override
    public ResponseEntity<Map<String, Long>> exportCount(String createdFrom, String createdTo, Boolean verified, String status, UUID categoryId,
            String q, BigDecimal minCost, BigDecimal maxCost, Integer minSales, BigDecimal minTrend) {
        // Cuenta con los MISMOS filtros que exporta: los segmentos que ofrece el panel salen de aquí, y si
        // contara de más ofrecería tramos que después vienen vacíos.
        return ResponseEntity.ok(Map.of("count", catalogUseCase.countProducts(
                filtroDeExportacion(createdFrom, createdTo, verified, status, categoryId, q, minCost, maxCost,
                        minSales, minTrend))));
    }

    /** Fecha ISO (yyyy-MM-dd) al inicio del día UTC; null si vacía. Para el límite inferior del rango. */
    private static Instant startOfDay(String isoDate) {
        LocalDate d = parseIsoDate(isoDate);
        return d == null ? null : d.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Fecha ISO al inicio del DÍA SIGUIENTE (límite superior EXCLUSIVO, para incluir todo el día indicado). */
    private static Instant endOfDayExclusive(String isoDate) {
        LocalDate d = parseIsoDate(isoDate);
        return d == null ? null : d.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Parsea una fecha ISO; vacía → null; inválida → 400 (IllegalArgumentException) en vez de 500. */
    private static LocalDate parseIsoDate(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(isoDate.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Fecha inválida (usa yyyy-MM-dd): " + isoDate);
        }
    }

    @Override
    public ResponseEntity<BulkProductDtoIn> exportProduct(UUID id) {
        return ResponseEntity.ok(catalogUseCase.exportProduct(id));
    }

    @Override
    public ResponseEntity<StreamingResponseBody> exportProductsNdjson(int batch, String createdFrom,
            String createdTo, Boolean verified, String status, UUID categoryId, String q, BigDecimal minCost,
            BigDecimal maxCost, Integer minSales, BigDecimal minTrend) {
        int safeBatch = Math.clamp(batch, 1, MAX_BATCH);
        CatalogUseCase.ExportFilter filtro = filtroDeExportacion(createdFrom, createdTo, verified, status,
                categoryId, q, minCost, maxCost, minSales, minTrend);
        // Un producto por línea, vaciando cada página: la memoria queda acotada a una página sea cual sea
        // el tamaño del catálogo. Se pagina por posición y no por clave desde que el filtro es el mismo que
        // el de la lista; el porqué está en `CatalogUseCase.exportPage`.
        StreamingResponseBody body = out -> {
            int pagina = 0;
            boolean hayMas = true;
            while (hayMas) {
                CatalogUseCase.ProductExportBatch lote = catalogUseCase.exportPage(pagina, safeBatch, filtro);
                for (BulkProductDtoIn dto : lote.items()) {
                    out.write(objectMapper.writeValueAsBytes(dto));
                    out.write('\n');
                }
                out.flush();
                hayMas = lote.hayMas();
                pagina++;
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/x-ndjson"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"products-export.ndjson\"")
                .body(body);
    }

    @Override
    public ResponseEntity<BulkResultDtoOut> importProductsNdjson(HttpServletRequest request, int batch) {
        int safeBatch = Math.clamp(batch, 1, MAX_BATCH);
        // Límites anti-DoS/OOM (el cuerpo se lee como stream crudo, sin los caps de multipart):
        //  · Content-Length declarado por encima del tope → se rechaza sin leer.
        //  · nº de registros y longitud de una línea acotados para no agotar memoria.
        if (request.getContentLengthLong() > MAX_IMPORT_BYTES) {
            throw new BusinessException("El fichero NDJSON supera el tamaño máximo permitido");
        }
        NdjsonImportAccumulator acc = new NdjsonImportAccumulator();
        List<BulkProductDtoIn> buffer = new ArrayList<>(safeBatch);
        long records = 0;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(request.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.length() > MAX_IMPORT_LINE_CHARS) {
                    throw new BusinessException("Una línea del NDJSON supera el tamaño máximo permitido");
                }
                if (++records > MAX_IMPORT_RECORDS) {
                    throw new BusinessException("El NDJSON supera el número máximo de registros ("
                            + MAX_IMPORT_RECORDS + ")");
                }
                BulkProductDtoIn row = parseNdjsonRow(line, acc);
                if (row == null) {
                    continue;
                }
                buffer.add(row);
                if (buffer.size() >= safeBatch) {
                    acc.merge(catalogUseCase.bulkCreateProducts(buffer));
                    buffer.clear();
                }
            }
        } catch (IOException ex) {
            throw new BusinessException("No se pudo leer el cuerpo NDJSON de importación");
        }
        if (!buffer.isEmpty()) {
            acc.merge(catalogUseCase.bulkCreateProducts(buffer));
        }
        return ResponseEntity.ok(acc.toResult());
    }

    /**
     * Convierte una línea NDJSON en fila de carga. Devuelve {@code null} —y anota el error en el
     * acumulador— si la línea está en blanco o no es JSON válido: una línea corrupta a mitad de un
     * fichero de miles no puede abortar la importación entera, se cuenta como fallida y se sigue.
     */
    private BulkProductDtoIn parseNdjsonRow(String line, NdjsonImportAccumulator acc) {
        if (line.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(line, BulkProductDtoIn.class);
        } catch (Exception ex) {
            acc.recordParseError(ex.getMessage());
            return null;
        }
    }

    /** Accumulates the per-batch results of an NDJSON import while keeping the error list bounded. */
    private static final class NdjsonImportAccumulator {
        private static final int MAX_ERRORS = 100;
        private int created;
        private int failed;
        private final List<String> errors = new ArrayList<>();

        void recordParseError(String message) {
            failed++;
            addError("parse: " + message);
        }

        void merge(BulkResultDtoOut batchResult) {
            created += batchResult.getCreated();
            failed += batchResult.getFailed();
            if (batchResult.getErrors() != null) {
                for (String error : batchResult.getErrors()) {
                    addError(error);
                }
            }
        }

        private void addError(String error) {
            if (errors.size() < MAX_ERRORS) {
                errors.add(error);
            }
        }

        BulkResultDtoOut toResult() {
            return new BulkResultDtoOut(created, failed, errors);
        }
    }

    @Override
    public ResponseEntity<BulkResultDtoOut> bulkCategories(List<BulkCategoryDtoIn> rows) {
        return ResponseEntity.ok(catalogUseCase.bulkCreateCategories(rows));
    }

    /* ===================== DROP-677: mapeo categorías 1688 → interna ===================== */

    @GetMapping("/category-1688-mappings")
    public ResponseEntity<List<Category1688MappingDtoOut>> listCategory1688Mappings() {
        return ResponseEntity.ok(catalogUseCase.listCategory1688Mappings());
    }

    @PostMapping("/category-1688-mappings")
    public ResponseEntity<UUID> upsertCategory1688Mapping(@RequestBody Map<String, String> body) {
        UUID categoryId = UUID.fromString(body.get("categoryId"));
        UUID id = catalogUseCase.upsertCategory1688Mapping(body.get("external1688Id"), body.get("external1688Name"),
                categoryId);
        return ResponseEntity.ok(id);
    }

    @DeleteMapping("/category-1688-mappings/{id}")
    public ResponseEntity<Void> deleteCategory1688Mapping(@PathVariable UUID id) {
        catalogUseCase.deleteCategory1688Mapping(id);
        return ResponseEntity.noContent().build();
    }

    /* ===================== DROP-670: esquema de atributos por categoría ===================== */

    @GetMapping("/categories/{categoryId}/attribute-schema")
    public ResponseEntity<List<CategoryAttributeSchemaDtoOut>> listCategoryAttributeSchema(
            @PathVariable UUID categoryId) {
        return ResponseEntity.ok(catalogUseCase.listCategoryAttributeSchema(categoryId));
    }

    @PostMapping("/categories/{categoryId}/attribute-schema")
    public ResponseEntity<UUID> upsertCategoryAttributeSchema(@PathVariable UUID categoryId,
            @RequestBody Map<String, Object> body) {
        String attrKey = (String) body.get("attrKey");
        String label = (String) body.get("label");
        boolean required = Boolean.TRUE.equals(body.get("required"));
        int position = body.get("position") instanceof Number n ? n.intValue() : 0;
        return ResponseEntity.ok(catalogUseCase.upsertCategoryAttributeSchema(categoryId, attrKey, label, required,
                position));
    }

    @DeleteMapping("/category-attribute-schema/{id}")
    public ResponseEntity<Void> deleteCategoryAttributeSchema(@PathVariable UUID id) {
        catalogUseCase.deleteCategoryAttributeSchema(id);
        return ResponseEntity.noContent().build();
    }
}
