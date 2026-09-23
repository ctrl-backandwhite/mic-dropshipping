package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper;
import com.nexaplatform.dropshipping.application.service.CustomsProfileService;
import com.nexaplatform.dropshipping.application.usecase.impl.CatalogUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.Category1688MappingRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryAttributeSchemaRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductImageRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductReviewJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductSpecificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.VariantValueRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito unit test for {@link CatalogUseCaseImpl}, mirroring the deleted
 * {@code CatalogServiceTest}. Mocks the domain port and the legacy
 * collaborators
 * used by the ingest/read flows.
 */
@ExtendWith(MockitoExtension.class)
class CatalogUseCaseImplTest {

    @Mock
    com.nexaplatform.dropshipping.domain.repository.ProductRepository productRepository;
    @Mock
    SupplierRepository supplierRepository;
    @Mock
    CategoryRepository categoryRepository;
    @Mock
    CustomsProfileService customsProfileService;
    @Mock
    ProductPriceTierRepository priceTierRepository;
    @Mock
    ProductImageRepository imageRepository;
    @Mock
    ObjectStorageService objectStorage;
    @Mock
    ProductRepository productJpaRepository;
    @Mock
    ProductMapper productMapper;
    @Mock
    CatalogStorefrontMapper catalogStorefrontMapper;
    @Mock
    KafkaTemplate<String, Object> kafkaTemplate;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    ProductIndexer productIndexer;
    @Mock
    CategoryIndexer categoryIndexer;
    @Mock
    ProductAttributeRepository productAttributeRepository;
    @Mock
    ProductSpecificationRepository productSpecificationRepository;
    @Mock
    VariantValueRepository variantValueRepository;
    @Mock
    JdbcTemplate jdbcTemplate;
    @Mock
    ProductBulkExportMapper bulkExportMapper;
    @Mock
    ImageMirrorService imageMirrorService;
    @Mock
    Category1688MappingRepository category1688MappingRepository;
    @Mock
    CategoryAttributeSchemaRepository categoryAttributeSchemaRepository;
    @Mock
    ProductReviewJpaRepositoryAdapter productReviewJpaRepositoryAdapter;
    @Mock
    org.springframework.beans.factory.ObjectProvider<com.nexaplatform.dropshipping.infrastructure.integration.bus.CatalogoBusService> busCatalogo;

    // Con @InjectMocks los colaboradores se pasan por el constructor por tipo:
    // añadir uno nuevo al
    // caso de uso ya no obliga a retocar esta lista de argumentos.
    @InjectMocks
    CatalogUseCaseImpl useCase;

    @Test
    void upsertSupplier_creates_when_missing() {
        when(supplierRepository.findBySourceAndExternalId("1688", "S1")).thenReturn(Optional.empty());
        when(supplierRepository.save(any(SupplierEntity.class))).thenAnswer(inv -> {
            SupplierEntity s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        SupplierEntity s = useCase.upsertSupplier(new IngestSupplierRequest("1688", "S1", "Acme", "艾克米", "CN", "Yiwu",
                new BigDecimal("4.7"), 5, true, true, null));

        assertThat(s.getId()).isNotNull();
        assertThat(s.getName()).isEqualTo("Acme");
        assertThat(s.isVerified()).isTrue();
    }

    @Test
    void upsertProduct_idempotent_with_same_external_id() {
        ProductEntity existing = ProductEntity.builder().source("1688").externalId("OFFER-1")
                .status(ProductStatus.DRAFT).titleZh("Old title").slug("old-slug-offer-1").moq(1).build();
        existing.setId(UUID.randomUUID());
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.of(existing));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest("1688", "OFFER-1", "Nuevo título", null, null, null, 2,
                new BigDecimal("19.90"), "CNY", 500, 1234, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/OFFER-1.html", null, null,
                List.of(new IngestImage("https://cbu01.alicdn.com/img.jpg", 0, "GALLERY")), null, null, null);

        ProductEntity saved = useCase.upsertProduct(req);
        assertThat(saved.getTitleZh()).isEqualTo("Nuevo título");
        assertThat(saved.getMoq()).isEqualTo(2);
        assertThat(saved.getImages()).hasSize(1);
        assertThat(saved.getImages().get(0).getSourceUrl()).contains("cbu01.alicdn");
        // slug preservado (no se regenera si ya existe)
        assertThat(saved.getSlug()).isEqualTo("old-slug-offer-1");
    }

    @Test
    void reimportar_actualizaLasVariantesPorExternalIdSinBorrarLasReferenciadasPorPedidos() {
        // El bulk es un UPSERT por JSON: reintentar un producto ya cargado debe
        // ACTUALIZAR sus variantes
        // en su mismo registro, no borrarlas y recrearlas (eso rompía la FK
        // order_item_variant_id_fkey
        // cuando un pedido referenciaba una variante, y la reimportación fallaba con
        // "registro que no
        // existe"). Las variantes que ya no vienen en el feed se desactivan, no se
        // borran.
        ProductEntity existing = ProductEntity.builder().source("1688").externalId("OFFER-V")
                .status(ProductStatus.DRAFT).titleZh("Old").slug("old-offer-v").moq(1).build();
        existing.setId(UUID.randomUUID());
        ProductVariantEntity v1 = ProductVariantEntity.builder().product(existing).externalId("V-1").sku("SKU-1")
                .title("Rojo S").price(new BigDecimal("10.00")).stock(5).active(true).build();
        ProductVariantEntity v2 = ProductVariantEntity.builder().product(existing).externalId("V-2").sku("SKU-2")
                .title("Rojo M").price(new BigDecimal("11.00")).stock(7).active(true).build();
        existing.setVariants(new java.util.ArrayList<>(List.of(v1, v2)));
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-V")).thenReturn(Optional.of(existing));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest("1688", "OFFER-V", "Título nuevo", null, null, null, 1,
                new BigDecimal("19.90"), "CNY", 500, 100, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/OFFER-V.html", null, null, List.of(), null,
                List.of(new IngestVariant("V-1", "SKU-1", "Rojo S", new BigDecimal("9.50"), 8, null, null),
                        new IngestVariant("V-3", "SKU-3", "Azul M", new BigDecimal("12.00"), 3, null, null)),
                null);

        ProductEntity saved = useCase.upsertProduct(req);

        // V-1 se actualizó en su mismo registro (no se borró), V-2 se desactivó (no se
        // borró), V-3 es nueva.
        assertThat(saved.getVariants()).hasSize(3);
        ProductVariantEntity v1Actualizado = saved.getVariants().stream().filter(v -> "V-1".equals(v.getExternalId()))
                .findFirst().orElseThrow();
        assertThat(v1Actualizado.getPrice()).isEqualByComparingTo("9.50");
        assertThat(v1Actualizado.getStock()).isEqualTo(8);
        assertThat(v1Actualizado.isActive()).isTrue();
        assertThat(v1Actualizado.getId()).isEqualTo(v1.getId());
        ProductVariantEntity v2Desactivado = saved.getVariants().stream().filter(v -> "V-2".equals(v.getExternalId()))
                .findFirst().orElseThrow();
        assertThat(v2Desactivado.isActive()).isFalse();
        assertThat(v2Desactivado.getId()).isEqualTo(v2.getId());
        assertThat(saved.getVariants()).anyMatch(v -> "V-3".equals(v.getExternalId()) && v.isActive());
    }

    @Test
    void upsertProduct_cjkTitle_doesNotProduceSlugStartingWithDash() {
        // Slugify descarta lo que no sea ASCII: con un título íntegramente en chino
        // devolvía "" y el slug
        // quedaba en "-<externalId>", una URL sin ninguna palabra. Debe caer en el
        // prefijo neutro.
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-CJK")).thenReturn(Optional.empty());
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest("1688", "OFFER-CJK", "真皮复古经典德训鞋女款", null, null, null, 1,
                new BigDecimal("42.90"), "CNY", 500, 100, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/OFFER-CJK.html", null, null, List.of(), null, null, null);

        ProductEntity saved = useCase.upsertProduct(req);

        assertThat(saved.getSlug()).doesNotStartWith("-");
        assertThat(saved.getSlug()).isEqualTo("product-offer-cjk");
    }

    @Test
    void upsertProduct_latinTitle_keepsReadableSlug() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-ES")).thenReturn(Optional.empty());
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest("1688", "OFFER-ES", "Bailarinas planas de mujer", null,
                null, null, 1, new BigDecimal("19.90"), "CNY", 500, 100, null, new BigDecimal("4.8"), 30,
                "https://detail.1688.com/offer/OFFER-ES.html", null, null, List.of(), null, null, null);

        ProductEntity saved = useCase.upsertProduct(req);

        assertThat(saved.getSlug()).isEqualTo("bailarinas-planas-de-mujer-offer-es");
    }

    @Test
    void listProductImages_delegatesToRepositoryAndMapper() {
        UUID id = UUID.randomUUID();
        when(imageRepository.findByProductIdOrderByPositionAsc(id)).thenReturn(List.of());
        when(catalogStorefrontMapper.toImageDtos(List.of())).thenReturn(List.of());

        assertThat(useCase.listProductImages(id)).isEmpty();
    }

    @Test
    void listProductPriceTiers_delegatesToRepositoryAndMapper() {
        UUID id = UUID.randomUUID();
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(id)).thenReturn(List.of());
        when(catalogStorefrontMapper.toPriceTierDtos(List.of())).thenReturn(List.of());

        assertThat(useCase.listProductPriceTiers(id)).isEmpty();
    }

    @Test
    void computeTrendScore_within_unit_range() {
        ProductEntity p = ProductEntity.builder().monthlySales(500).rating(new BigDecimal("4.5"))
                .repurchaseRate(new BigDecimal("30")).reviewCount(100).build();
        BigDecimal score = useCase.computeTrendScore(p);
        assertThat(score).isGreaterThan(BigDecimal.ZERO).isLessThanOrEqualTo(BigDecimal.ONE);
    }

    // ============ DROP-158: recargo fijo por producto (30-ago-2026) ============

    /** Update masivo del recargo para TODO el catálogo: un único UPDATE sin filtro. */
    @Test
    void bulkUpdateSurcharge_sinFiltro_actualizaTodoElCatalogo() {
        when(jdbcTemplate.update("UPDATE product SET surcharge_cny = ?, updated_at = now()", new BigDecimal("2.00")))
                .thenReturn(1234);

        int n = useCase.bulkUpdateSurcharge(null, null, new BigDecimal("2.00"));

        assertThat(n).isEqualTo(1234);
    }

    /** Update masivo del recargo por categoría: WHERE category_id = ?. */
    @Test
    void bulkUpdateSurcharge_porCategoria_filtraPorCategoria() {
        UUID cat = UUID.randomUUID();
        when(jdbcTemplate.update("UPDATE product SET surcharge_cny = ?, updated_at = now() WHERE category_id = ?",
                new BigDecimal("5.00"), cat)).thenReturn(42);

        int n = useCase.bulkUpdateSurcharge(null, cat, new BigDecimal("5.00"));

        assertThat(n).isEqualTo(42);
    }

    /** Update masivo del recargo por producto: WHERE id IN (…). */
    @Test
    void bulkUpdateSurcharge_porProductos_filtraPorIds() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(jdbcTemplate.update(
                org.mockito.ArgumentMatchers
                        .startsWith("UPDATE product SET surcharge_cny = ?, updated_at = now() WHERE id IN ("),
                org.mockito.ArgumentMatchers.<Object>any(), org.mockito.ArgumentMatchers.<Object>any(),
                org.mockito.ArgumentMatchers.<Object>any())).thenReturn(2);

        int n = useCase.bulkUpdateSurcharge(List.of(a, b), null, new BigDecimal("3.00"));

        assertThat(n).isEqualTo(2);
    }

    /** Recargo null se trata como 0 (reset del recargo). */
    @Test
    void bulkUpdateSurcharge_nullSeTrataComoCero() {
        when(jdbcTemplate.update("UPDATE product SET surcharge_cny = ?, updated_at = now()", BigDecimal.ZERO))
                .thenReturn(7);

        int n = useCase.bulkUpdateSurcharge(null, null, null);

        assertThat(n).isEqualTo(7);
    }

    /**
     * El update masivo deja marcados para el bus los certificados afectados, en UNA sentencia.
     *
     * <p>Antes se traían los ids y se cargaba cada producto para marcarlo uno a uno: aplicar un recargo
     * a todo el catálogo eran miles de consultas dentro de la petición y el administrador se quedaba
     * mirando la pantalla hasta tener que recargarla. Si esta prueba vuelve a pasar cargando entidades,
     * esa espera ha vuelto.
     */
    @Test
    void bulkUpdateSurcharge_dejaMarcadosLosCertificadosEnUnaSolaSentencia() {
        when(jdbcTemplate.update("UPDATE product SET surcharge_cny = ?, updated_at = now()", new BigDecimal("2.00")))
                .thenReturn(3);
        when(busCatalogo.getIfAvailable()).thenReturn(
                mock(com.nexaplatform.dropshipping.infrastructure.integration.bus.CatalogoBusService.class));

        int n = useCase.bulkUpdateSurcharge(null, null, new BigDecimal("2.00"));

        assertThat(n).isEqualTo(3);
        verify(jdbcTemplate).update("UPDATE product SET bus_estado = 'PENDIENTE', bus_intentos = 0, "
                + "bus_error = null WHERE verified = true");
        verify(productJpaRepository, never()).findById(any());
    }

    /** Sin bus configurado no se marca nada: la cola no debe llenarse en un entorno que no publica. */
    @Test
    void bulkUpdateSurcharge_sinBusNoMarcaNada() {
        when(jdbcTemplate.update("UPDATE product SET surcharge_cny = ?, updated_at = now()", new BigDecimal("2.00")))
                .thenReturn(3);
        when(busCatalogo.getIfAvailable()).thenReturn(null);

        useCase.bulkUpdateSurcharge(null, null, new BigDecimal("2.00"));

        verify(jdbcTemplate, never()).update(contains("bus_estado"));
    }

    /**
     * Fija el producto con su galería y, detrás, las fotos de la descripción. Es la forma que tienen
     * en la base de datos desde el 12-sep-2026: una sola secuencia de posiciones, primero el carrusel
     * y después el detalle.
     */
    private static ProductEntity conGaleriaYDetalle(UUID... ids) {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("OFFER-9").status(ProductStatus.DRAFT)
                .slug("offer-9").moq(1).build();
        p.setId(UUID.randomUUID());
        String[] papeles = {"MAIN", "GALLERY", "DETAIL", "DETAIL", "DETAIL"};
        for (int i = 0; i < ids.length; i++) {
            ProductImageEntity img = ProductImageEntity.builder().product(p).position(i).role(papeles[i])
                    .sourceUrl("https://x/" + i + ".jpg").build();
            img.setId(ids[i]);
            p.getImages().add(img);
        }
        return p;
    }

    private static ProductImageEntity porId(ProductEntity p, UUID id) {
        return p.getImages().stream().filter(i -> id.equals(i.getId())).findFirst().orElseThrow();
    }

    /**
     * Lo que se rompería en producción si esta prueba fallara: arrastrar una foto en la galería de la
     * DESCRIPCIÓN la convertiría en foto del carrusel —y la primera, en la imagen principal del
     * producto—, porque el reordenado reasigna el papel por posición. El comprador vería un cartel de
     * medidas como foto de portada y las fotos de la descripción desaparecerían de su sección.
     */
    @Test
    void reorderProductImages_reordenarElDetalleNoLoConvierteEnGaleria() {
        UUID principal = UUID.randomUUID();
        UUID galeria = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        ProductEntity p = conGaleriaYDetalle(principal, galeria, d1, d2, d3);
        when(productJpaRepository.findById(p.getId())).thenReturn(Optional.of(p));

        useCase.reorderProductImages(p.getId(), List.of(d3, d1, d2));

        assertThat(porId(p, d3).getRole()).isEqualTo("DETAIL");
        assertThat(porId(p, d1).getRole()).isEqualTo("DETAIL");
        assertThat(porId(p, d2).getRole()).isEqualTo("DETAIL");
        // Las tres ocupan los MISMOS huecos de antes (2, 3 y 4), en el orden pedido.
        assertThat(porId(p, d3).getPosition()).isEqualTo(2);
        assertThat(porId(p, d1).getPosition()).isEqualTo(3);
        assertThat(porId(p, d2).getPosition()).isEqualTo(4);
        // Y el carrusel no se entera: ni papel ni posición.
        assertThat(porId(p, principal).getRole()).isEqualTo("MAIN");
        assertThat(porId(p, principal).getPosition()).isZero();
        assertThat(porId(p, galeria).getRole()).isEqualTo("GALLERY");
        assertThat(porId(p, galeria).getPosition()).isEqualTo(1);
    }

    /**
     * El otro lado del mismo cambio: reordenar el CARRUSEL tiene que seguir haciendo lo de siempre
     * —la primera pasa a principal— y dejar las fotos de la descripción detrás, sin cambiarles el
     * papel. Sin esta prueba, arreglar el detalle podía romper lo que ya funcionaba.
     */
    @Test
    void reorderProductImages_reordenarLaGaleriaDejaElDetalleDetrasYConSuPapel() {
        UUID principal = UUID.randomUUID();
        UUID galeria = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        UUID d3 = UUID.randomUUID();
        ProductEntity p = conGaleriaYDetalle(principal, galeria, d1, d2, d3);
        when(productJpaRepository.findById(p.getId())).thenReturn(Optional.of(p));

        useCase.reorderProductImages(p.getId(), List.of(galeria, principal));

        assertThat(porId(p, galeria).getRole()).isEqualTo("MAIN");
        assertThat(porId(p, galeria).getPosition()).isZero();
        assertThat(porId(p, principal).getRole()).isEqualTo("GALLERY");
        assertThat(porId(p, principal).getPosition()).isEqualTo(1);
        assertThat(porId(p, d1).getRole()).isEqualTo("DETAIL");
        assertThat(porId(p, d2).getRole()).isEqualTo("DETAIL");
        assertThat(porId(p, d3).getRole()).isEqualTo("DETAIL");
        assertThat(List.of(porId(p, d1).getPosition(), porId(p, d2).getPosition(), porId(p, d3).getPosition()))
                .containsExactly(2, 3, 4);
    }

    /**
     * Lo que se rompía en producción: CADA re-subida de un producto descartaba el espejado de TODAS
     * sus imágenes, aunque su dirección de origen no hubiera cambiado. `replaceImages` vaciaba la
     * colección y las recreaba en PENDING.
     *
     * <p>Dos daños. El visible: el escaparate solo enseña productos con imagen espejada, así que el
     * producto DESAPARECE de la tienda hasta que el espejado vuelve a pasar —el dueño lo vio el
     * 14-sep: «porque ahora se ven menos productos de los que veía en la home»—. Y el caro: volver a
     * descargar y subir cada foto. Sobre el repaso del catálogo entero son ~200.000 imágenes
     * re-espejadas para nada.
     *
     * <p>Una imagen cuya dirección de origen no cambia ES la misma imagen. Lo que ya está espejado se
     * conserva.
     */
    @Test
    void reimportar_conserva_el_espejado_de_las_imagenes_que_no_cambian() {
        ProductEntity p = ProductEntity.builder().source("1688").externalId("OFFER-IMG").status(ProductStatus.DRAFT)
                .slug("offer-img").moq(1).build();
        p.setId(UUID.randomUUID());
        ProductImageEntity yaEspejada = ProductImageEntity.builder().product(p).position(0).role("MAIN")
                .sourceUrl("https://cbu01.alicdn.com/img/ibank/O1CN01a.jpg").cdnUrl("https://cdn/media/ab/abc.webp")
                .mirrorStatus(MirrorStatus.MIRRORED).build();
        yaEspejada.setId(UUID.randomUUID());
        p.getImages().add(yaEspejada);
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-IMG")).thenReturn(Optional.of(p));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        IngestProductRequest req = new IngestProductRequest("1688", "OFFER-IMG", "标题", null, null, null, 1,
                new BigDecimal("10.00"), "CNY", 100, 0, null, null, 0, "https://detail.1688.com/offer/OFFER-IMG.html",
                null, null,
                List.of(new IngestImage("https://cbu01.alicdn.com/img/ibank/O1CN01a.jpg", 0, "MAIN"),
                        new IngestImage("https://cbu01.alicdn.com/img/ibank/O1CN01b.jpg", 1, "GALLERY")),
                null, null, null);

        ProductEntity guardado = useCase.upsertProduct(req);

        ProductImageEntity misma = guardado.getImages().stream().filter(i -> i.getSourceUrl().endsWith("O1CN01a.jpg"))
                .findFirst().orElseThrow();
        assertThat(misma.getCdnUrl()).isEqualTo("https://cdn/media/ab/abc.webp");
        assertThat(misma.getMirrorStatus()).isEqualTo(MirrorStatus.MIRRORED);

        // La nueva sí entra pendiente: esa no se ha espejado nunca.
        ProductImageEntity nueva = guardado.getImages().stream().filter(i -> i.getSourceUrl().endsWith("O1CN01b.jpg"))
                .findFirst().orElseThrow();
        assertThat(nueva.getMirrorStatus()).isEqualTo(MirrorStatus.PENDING);
        assertThat(nueva.getCdnUrl()).isNull();
    }
}
