package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.domain.enums.ReviewSource;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestCategoryRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestImage;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestPriceTier;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestProductRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestSupplierRequest;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariant;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantOption;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.IngestVariantValue;
import com.nexaplatform.dropshipping.api.dto.in.BulkCategoryDtoIn;
import com.nexaplatform.dropshipping.api.dto.in.BulkProductDtoIn;
import com.nexaplatform.dropshipping.api.dto.out.BulkResultDtoOut;
import com.nexaplatform.dropshipping.api.exception.BusinessException;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper;
import com.nexaplatform.dropshipping.application.service.CustomsProfileService;
import com.nexaplatform.dropshipping.application.usecase.CatalogUseCase;
import com.nexaplatform.dropshipping.application.usecase.impl.CatalogUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.Category1688MappingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryAttributeSchemaEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductReviewEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
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
import com.nexaplatform.dropshipping.infrastructure.seed.CatalogFillWriter;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import com.nexaplatform.dropshipping.infrastructure.integration.bus.CatalogoBusService;
import org.mockito.InjectMocks;
import org.springframework.beans.factory.ObjectProvider;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas de la IMPORTACIÓN MASIVA del catálogo (bulk/ingest/categorías) en {@link CatalogUseCaseImpl}:
 * qué exige una fila para entrar, qué se reemplaza al reimportar y cómo se aísla el fallo de una fila.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov01CatalogBulkImportTest {

    private static final UUID CATEGORY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID PRODUCT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SUPPLIER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String CATEGORY_SLUG = "moda-mujer";

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

    /**
     * El bus de integración está apagado en las pruebas: sin él, publicar no forma parte de lo que
     * se está comprobando aquí. Hace falta declararlo igualmente porque Mockito solo inyecta los
     * colaboradores que se le nombran, y el que falta llega como null.
     */
    @Mock
    private ObjectProvider<CatalogoBusService> busCatalogo;

    @InjectMocks
    CatalogUseCaseImpl useCase;

    /** Auto-referencia por el proxy y colaboradores inyectados por campo (@Lazy / @PersistenceContext). */
    @Mock
    CatalogUseCase self;
    @Mock
    CatalogFillWriter catalogFillWriter;
    @Mock
    EntityManager em;

    private CategoryEntity category;
    private SupplierEntity defaultSupplier;
    private ProductEntity managed;

    @BeforeEach
    void setUp() {
        // Los campos @Lazy/@PersistenceContext no entran por constructor: Mockito no los inyecta.
        ReflectionTestUtils.setField(useCase, "self", self);
        ReflectionTestUtils.setField(useCase, "catalogFillWriter", catalogFillWriter);
        ReflectionTestUtils.setField(useCase, "em", em);

        category = CategoryEntity.builder().slug(CATEGORY_SLUG).build();
        category.setId(CATEGORY_ID);
        defaultSupplier = SupplierEntity.builder().source("1688").externalId("SUP-DEF").name("Proveedor por defecto")
                .build();
        defaultSupplier.setId(SUPPLIER_ID);
        managed = ProductEntity.builder().build();
        managed.setId(PRODUCT_ID);
        managed.setCategory(category);

        when(categoryRepository.findBySlug(CATEGORY_SLUG)).thenReturn(Optional.of(category));
        when(categoryAttributeSchemaRepository.findByCategory_IdOrderByPositionAsc(CATEGORY_ID))
                .thenReturn(List.of());
        when(supplierRepository.findAll()).thenReturn(List.of(defaultSupplier));
        when(supplierRepository.save(any(SupplierEntity.class))).thenAnswer(inv -> {
            SupplierEntity s = inv.getArgument(0);
            if (s.getId() == null) {
                s.setId(UUID.randomUUID());
            }
            return s;
        });
        when(productJpaRepository.findFirstByExternalId(anyString())).thenReturn(Optional.empty());
        when(productJpaRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(managed));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        // El escritor real corre en su propia transacción; aquí sólo se ejecuta el hook de enriquecido
        // sobre la entidad gestionada, que es lo que fija logística, atributos y ficha técnica.
        when(catalogFillWriter.write(any(IngestProductRequest.class), any(), any(), any(), any(), any(), any(), any(),
                any(), any())).thenAnswer(inv -> {
                    Consumer<ProductEntity> enrich = inv.getArgument(9);
                    enrich.accept(managed);
                    return PRODUCT_ID;
                });
    }

    /* ============ utilidades ============ */

    private static BulkProductDtoIn validRow() {
        BulkProductDtoIn r = new BulkProductDtoIn();
        r.setCategorySlug(CATEGORY_SLUG);
        r.setTitleEs("Camiseta de algodón");
        r.setPrice(new BigDecimal("12.50"));
        r.setShippingCny(new BigDecimal("10"));
        r.setIvaCny(new BigDecimal("2"));
        r.setSurchargeCny(new BigDecimal("3"));
        r.setImageUrls(List.of("https://cbu01.alicdn.com/a.jpg"));
        r.setExternalId("OFFER-1");
        return r;
    }

    private IngestProductRequest capturedIngest() {
        ArgumentCaptor<IngestProductRequest> captor = ArgumentCaptor.forClass(IngestProductRequest.class);
        verify(catalogFillWriter).write(captor.capture(), any(), any(), any(), any(), any(), any(), any(), any(),
                any());
        return captor.getValue();
    }

    private String capturedSpanishTitle() {
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(catalogFillWriter).write(any(), captor.capture(), any(), any(), any(), any(), any(), any(), any(),
                any());
        return captor.getValue();
    }

    /* ============ proveedores (ingesta) ============ */

    @Test
    void elProveedorSeReutilizaPorOrigenEIdExternoEnVezDeDuplicarse() {
        SupplierEntity existing = SupplierEntity.builder().source("1688").externalId("S1").name("Viejo").build();
        existing.setId(SUPPLIER_ID);
        when(supplierRepository.findBySourceAndExternalId("1688", "S1")).thenReturn(Optional.of(existing));

        SupplierEntity saved = useCase.upsertSupplier(new IngestSupplierRequest("1688", "S1", "Nuevo", null, null, null,
                null, null, false, false, null));

        assertThat(saved.getId()).isEqualTo(SUPPLIER_ID);
        assertThat(saved.getName()).isEqualTo("Nuevo");
    }

    @Test
    void unProveedorSinPaisDeclaradoSeGuardaComoChino() {
        // Todo el catálogo viene de 1688: sin país no se puede declarar el origen en aduana.
        when(supplierRepository.findBySourceAndExternalId("1688", "S2")).thenReturn(Optional.empty());

        SupplierEntity saved = useCase.upsertSupplier(new IngestSupplierRequest("1688", "S2", "Fábrica", null, null,
                null, null, null, false, false, null));

        assertThat(saved.getCountry()).isEqualTo("CN");
    }

    /* ============ categorías (ingesta) ============ */

    @Test
    void crearUnaCategoriaConSlugYaExistenteSeRechaza() {
        IngestCategoryRequest req = new IngestCategoryRequest(CATEGORY_SLUG, null, "1688", null, "女装", 1, "tag",
                null);

        assertThatThrownBy(() -> useCase.createCategoryRejectingDuplicateSlug(req))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(CATEGORY_SLUG);
    }

    @Test
    void unaCategoriaNuevaNaceActivaYConOrigen1688PorDefecto() {
        when(categoryRepository.findBySlug("zapatos")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(CategoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryEntity saved = useCase.upsertCategory(new IngestCategoryRequest("zapatos", null, null, null, "鞋", 3,
                "shoe", null));

        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getSource()).isEqualTo("1688");
        assertThat(saved.getPosition()).isEqualTo(3);
    }

    @Test
    void laTraduccionDeUnaCategoriaExistenteSeActualizaEnSitioSinDuplicarla() {
        // Reimportar la taxonomía es habitual: acumular filas rompería la unique (categoría, idioma).
        CategoryEntity existing = CategoryEntity.builder().slug("zapatos").build();
        existing.setId(UUID.randomUUID());
        existing.getTranslations()
                .add(CategoryTranslationEntity.builder().category(existing).language("es").name("Viejo").build());
        when(categoryRepository.findBySlug("zapatos")).thenReturn(Optional.of(existing));
        when(categoryRepository.save(any(CategoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryEntity saved = useCase.upsertCategory(new IngestCategoryRequest("zapatos", null, "1688", null, "鞋", 1,
                "shoe", Map.of("es", "Zapatos")));

        assertThat(saved.getTranslations()).hasSize(1);
        assertThat(saved.getTranslations().get(0).getName()).isEqualTo("Zapatos");
    }

    @Test
    void unaTraduccionEnBlancoDeLaCategoriaNoSeGuarda() {
        // Guardarla dejaría el nombre vacío en ese idioma y el menú saldría con un hueco.
        when(categoryRepository.findBySlug("zapatos")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(CategoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        Map<String, String> names = new LinkedHashMap<>();
        names.put("es", "Zapatos");
        names.put("en", "   ");

        CategoryEntity saved = useCase
                .upsertCategory(new IngestCategoryRequest("zapatos", null, "1688", null, "鞋", 1, "shoe", names));

        assertThat(saved.getTranslations()).hasSize(1);
        assertThat(saved.getTranslations().get(0).getLanguage()).isEqualTo("es");
    }

    @Test
    void laCategoriaConTraduccionesSeGuardaDosVecesParaQueLaCascadaLasPersista() {
        // Las filas de traducción se añaden DESPUÉS del primer save; sin el segundo se perdían en silencio.
        when(categoryRepository.findBySlug("zapatos")).thenReturn(Optional.empty());
        when(categoryRepository.save(any(CategoryEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        useCase.upsertCategory(new IngestCategoryRequest("zapatos", null, "1688", null, "鞋", 1, "shoe",
                Map.of("es", "Zapatos")));

        verify(categoryRepository, times(2)).save(any(CategoryEntity.class));
        verify(categoryIndexer).indexCategory(any());
    }

    /* ============ upsertProduct (ingesta) ============ */

    private static IngestProductRequest ingestRequest(List<IngestImage> images, List<IngestVariantOption> options,
            List<IngestVariant> variants, List<IngestPriceTier> tiers) {
        return new IngestProductRequest("1688", "OFFER-1", "标题", null, null, null, null, new BigDecimal("9.90"),
                null, 500, null, null, null, null, null, null, null, images, options, variants, tiers);
    }

    @Test
    void lasColeccionesHijasSeReemplazanEnteradasParaQueReimportarSeaIdempotente() {
        ProductEntity existing = ProductEntity.builder().source("1688").externalId("OFFER-1").slug("s").build();
        existing.setId(PRODUCT_ID);
        existing.getImages().add(ProductImageEntity.builder().product(existing).sourceUrl("vieja-1.jpg").build());
        existing.getImages().add(ProductImageEntity.builder().product(existing).sourceUrl("vieja-2.jpg").build());
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.of(existing));

        ProductEntity saved = useCase.upsertProduct(
                ingestRequest(List.of(new IngestImage("nueva.jpg", 0, "MAIN")), null, null, null));

        assertThat(saved.getImages()).hasSize(1);
        assertThat(saved.getImages().get(0).getSourceUrl()).isEqualTo("nueva.jpg");
        assertThat(saved.getImages().get(0).getMirrorStatus()).isEqualTo(MirrorStatus.PENDING);
    }

    @Test
    void unaImagenSinRolDeclaradoEntraEnLaGaleria() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.empty());

        ProductEntity saved = useCase
                .upsertProduct(ingestRequest(List.of(new IngestImage("a.jpg", 0, null)), null, null, null));

        assertThat(saved.getImages().get(0).getRole()).isEqualTo("GALLERY");
    }

    @Test
    void laImagenPrincipalEsLaDeRolMainAunqueNoSeaLaPrimeraPosicion() {
        // De ella cuelgan las variantes sin foto propia: elegir mal deja al comprador viendo otro color.
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.empty());
        List<IngestImage> images = List.of(new IngestImage("galeria.jpg", 0, "GALLERY"),
                new IngestImage("principal.jpg", 5, "MAIN"));

        ProductEntity saved = useCase.upsertProduct(ingestRequest(images, null,
                List.of(new IngestVariant("V1", "SKU-1", "Roja", new BigDecimal("9.90"), 3, null, null)), null));

        assertThat(saved.getVariants().get(0).getImageSourceUrl()).isEqualTo("principal.jpg");
    }

    @Test
    void unaVarianteConFotoPropiaConservaLaSuya() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.empty());

        ProductEntity saved = useCase.upsertProduct(ingestRequest(List.of(new IngestImage("principal.jpg", 0, "MAIN")),
                null, List.of(new IngestVariant("V1", "SKU-1", "Roja", null, null, "roja.jpg", null)), null));

        assertThat(saved.getVariants().get(0).getImageSourceUrl()).isEqualTo("roja.jpg");
    }

    @Test
    void unValorDeVariacionSinFotoPropiaHeredaLaPrincipalDelProducto() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.empty());
        List<IngestVariantOption> options = List.of(new IngestVariantOption("颜色", 0,
                List.of(new IngestVariantValue("红色", 0, null), new IngestVariantValue("蓝色", 1, "azul.jpg"))));

        ProductEntity saved = useCase.upsertProduct(
                ingestRequest(List.of(new IngestImage("principal.jpg", 0, "MAIN")), options, null, null));

        assertThat(saved.getVariantOptions().get(0).getValues().get(0).getImageSourceUrl())
                .isEqualTo("principal.jpg");
        assertThat(saved.getVariantOptions().get(0).getValues().get(1).getImageSourceUrl()).isEqualTo("azul.jpg");
    }

    @Test
    void unaVarianteSinStockDeclaradoSeGuardaACeroYNoSeInventa() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.empty());

        ProductEntity saved = useCase.upsertProduct(ingestRequest(List.of(), null,
                List.of(new IngestVariant("V1", "SKU-1", "Roja", null, null, null, null)), null));

        assertThat(saved.getVariants().get(0).getStock()).isZero();
        assertThat(saved.getVariants().get(0).isActive()).isTrue();
    }

    @Test
    void losTramosDePrecioSeBorranYSeRecreanEnCadaIngesta() {
        // Viven en su propia tabla (sin cascada): acumularlos dejaría tramos viejos cobrando precios viejos.
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.empty());
        ProductPriceTierEntity old = ProductPriceTierEntity.builder().minQty(1).build();
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(any())).thenReturn(List.of(old));

        useCase.upsertProduct(ingestRequest(List.of(), null, null,
                List.of(new IngestPriceTier(10, 99, new BigDecimal("8.00"), null))));

        verify(priceTierRepository).delete(old);
        ArgumentCaptor<ProductPriceTierEntity> captor = ArgumentCaptor.forClass(ProductPriceTierEntity.class);
        verify(priceTierRepository).save(captor.capture());
        assertThat(captor.getValue().getMinQty()).isEqualTo(10);
        assertThat(captor.getValue().getCurrency()).isEqualTo("CNY");
    }

    @Test
    void losValoresQueNoTraeLaIngestaCaenASusPorDefecto() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.empty());

        ProductEntity saved = useCase.upsertProduct(ingestRequest(List.of(), null, null, null));

        assertThat(saved.getCurrency()).isEqualTo("CNY");
        assertThat(saved.getMoq()).isEqualTo(1);
        assertThat(saved.getMonthlySales()).isZero();
        assertThat(saved.getReviewCount()).isZero();
        assertThat(saved.getStatus()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void sinIdentificadorAsignadoNoSePublicaElEventoDeIndexacion() {
        // El id lo pone JPA al volcar; publicar sin él dejaría un evento que nadie puede resolver.
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.empty());

        useCase.upsertProduct(ingestRequest(List.of(), null, null, null));

        verifyNoInteractions(kafkaTemplate);
    }

    @Test
    void conIdentificadorAsignadoSePublicaElEventoDeProductoIngestado() {
        ProductEntity existing = ProductEntity.builder().source("1688").externalId("OFFER-1").slug("s").build();
        existing.setId(PRODUCT_ID);
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.of(existing));

        useCase.upsertProduct(ingestRequest(List.of(), null, null, null));

        verify(kafkaTemplate).send(anyString(), eq(PRODUCT_ID.toString()), any());
    }

    /* ============ carga masiva de productos ============ */

    @Test
    void unaFilaMalaNoAbortaLaCargaYSeReportaConSuNumeroDeFila() {
        // Un lote son cientos de filas: cortar en la primera mala obligaría a repetirlo entero.
        BulkProductDtoIn sinPrecio = validRow();
        sinPrecio.setPrice(null);

        BulkResultDtoOut result = useCase.bulkCreateProducts(List.of(validRow(), sinPrecio, validRow()));

        assertThat(result.getCreated()).isEqualTo(2);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().get(0)).startsWith("Fila 2: ").contains("precio");
    }

    @Test
    void unaFilaSinImagenNoEntraEnElCatalogo() {
        BulkProductDtoIn sinImagen = validRow();
        sinImagen.setImageUrls(List.of());

        BulkResultDtoOut result = useCase.bulkCreateProducts(List.of(sinImagen));

        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0)).contains("no tiene imágenes");
    }

    @Test
    void unaFilaSinEnvioOSinIvaNoEntraEnElCatalogo() {
        // El total es base×margen + IVA + envío: sin ellos el producto se vendería por debajo de coste.
        BulkProductDtoIn sinEnvio = validRow();
        sinEnvio.setShippingCny(null);
        BulkProductDtoIn sinIva = validRow();
        sinIva.setIvaCny(null);

        BulkResultDtoOut result = useCase.bulkCreateProducts(List.of(sinEnvio, sinIva));

        assertThat(result.getFailed()).isEqualTo(2);
        assertThat(result.getErrors().get(0)).contains("envío");
        assertThat(result.getErrors().get(1)).contains("IVA");
    }

    @Test
    void losProveedoresSeLeenUnaSolaVezParaTodoElLote() {
        // Leerlos por fila convertía una carga de 500 productos en 500 consultas idénticas.
        useCase.bulkCreateProducts(List.of(validRow(), validRow(), validRow()));

        verify(supplierRepository, times(1)).findAll();
    }

    @Test
    void soloSeIndexanYEspejanLosProductosReciencreados() {
        BulkProductDtoIn mala = validRow();
        mala.setTitleEs(null);

        useCase.bulkCreateProducts(List.of(validRow(), mala));

        verify(productIndexer, times(1)).indexProduct(PRODUCT_ID);
        verify(imageMirrorService).mirrorProductsAsync(List.of(PRODUCT_ID));
    }

    @Test
    void unLoteSinAltasNoDisparaElEspejadoDeImagenes() {
        BulkProductDtoIn mala = validRow();
        mala.setTitleEs(null);

        useCase.bulkCreateProducts(List.of(mala));

        verifyNoInteractions(imageMirrorService);
        verify(productIndexer, never()).indexProduct(any());
    }

    @Test
    void elAltaIndividualTambienIndexaYEspejaSuImagen() {
        UUID id = useCase.createProductManual(validRow());

        assertThat(id).isEqualTo(PRODUCT_ID);
        verify(productIndexer).indexProduct(PRODUCT_ID);
        verify(imageMirrorService).mirrorProductsAsync(List.of(PRODUCT_ID));
    }

    /* ============ resolución de categoría de la fila ============ */

    @Test
    void elSlugInternoTienePrioridadSobreElMapeoDe1688() {
        BulkProductDtoIn r = validRow();
        r.setCategory1688Id("999");

        useCase.createProductManual(r);

        assertThat(capturedIngest().categoryId()).isEqualTo(CATEGORY_ID);
        verifyNoInteractions(category1688MappingRepository);
    }

    @Test
    void unSlugDeCategoriaDesconocidoRechazaLaFilaSinInventarla() {
        BulkProductDtoIn r = validRow();
        r.setCategorySlug("no-existe");
        when(categoryRepository.findBySlug("no-existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.createProductManual(r)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Categoría no encontrada");
    }

    @Test
    void sinSlugInternoLaCategoriaSeResuelvePorElIdDe1688() {
        BulkProductDtoIn r = validRow();
        r.setCategorySlug(null);
        r.setCategory1688Id(" 555 ");
        Category1688MappingEntity mapping = Category1688MappingEntity.builder().category(category).build();
        when(category1688MappingRepository.findByExternal1688Id("555")).thenReturn(Optional.of(mapping));

        useCase.createProductManual(r);

        assertThat(capturedIngest().categoryId()).isEqualTo(CATEGORY_ID);
    }

    @Test
    void sinIdMapeadoLaCategoriaSeResuelvePorElNombreDe1688() {
        BulkProductDtoIn r = validRow();
        r.setCategorySlug(null);
        r.setCategory1688Id("555");
        r.setCategory1688Name("女装");
        when(category1688MappingRepository.findByExternal1688Id("555")).thenReturn(Optional.empty());
        when(category1688MappingRepository.findFirstByExternal1688NameIgnoreCase("女装"))
                .thenReturn(Optional.of(Category1688MappingEntity.builder().category(category).build()));

        useCase.createProductManual(r);

        assertThat(capturedIngest().categoryId()).isEqualTo(CATEGORY_ID);
    }

    @Test
    void sinNingunaPistaDeCategoriaLaFilaSeRechaza() {
        BulkProductDtoIn r = validRow();
        r.setCategorySlug(null);

        assertThatThrownBy(() -> useCase.createProductManual(r)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("No se pudo resolver la categoría");
    }

    @Test
    void unaCategoriaConAtributosObligatoriosRechazaLaFilaQueNoLosTrae() {
        // DROP-670: importar sin ellos deja fichas incompletas que hay que repasar producto a producto.
        CategoryAttributeSchemaEntity required = CategoryAttributeSchemaEntity.builder().attrKey("material")
                .required(true).build();
        when(categoryAttributeSchemaRepository.findByCategory_IdOrderByPositionAsc(CATEGORY_ID))
                .thenReturn(List.of(required));
        BulkProductDtoIn r = validRow();

        assertThatThrownBy(() -> useCase.createProductManual(r)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("material");
    }

    /* ============ resolución de proveedor de la fila ============ */

    @Test
    void unProveedorDe1688YaConocidoSeReutilizaPorSuIdExterno() {
        BulkProductDtoIn r = validRow();
        r.setSupplierExternalId(" SUP-9 ");
        SupplierEntity existing = SupplierEntity.builder().source("1688").externalId("SUP-9").build();
        existing.setId(SUPPLIER_ID);
        when(supplierRepository.findBySourceAndExternalId("1688", "SUP-9")).thenReturn(Optional.of(existing));

        useCase.createProductManual(r);

        assertThat(capturedIngest().supplierId()).isEqualTo(SUPPLIER_ID);
        verify(supplierRepository, never()).save(any(SupplierEntity.class));
    }

    @Test
    void unProveedorDe1688DesconocidoSeCreaEnVezDeRechazarLaFila() {
        BulkProductDtoIn r = validRow();
        r.setSupplierExternalId("SUP-NUEVO");
        r.setSupplierName("Fábrica Textil");
        when(supplierRepository.findBySourceAndExternalId("1688", "SUP-NUEVO")).thenReturn(Optional.empty());

        useCase.createProductManual(r);

        ArgumentCaptor<SupplierEntity> captor = ArgumentCaptor.forClass(SupplierEntity.class);
        verify(supplierRepository).save(captor.capture());
        assertThat(captor.getValue().getExternalId()).isEqualTo("SUP-NUEVO");
        assertThat(captor.getValue().getName()).isEqualTo("Fábrica Textil");
        assertThat(captor.getValue().getCountry()).isEqualTo("CN");
    }

    @Test
    void unProveedorNuevoSinNombreSeBautizaConSuIdExterno() {
        BulkProductDtoIn r = validRow();
        r.setSupplierExternalId("SUP-XYZ");
        when(supplierRepository.findBySourceAndExternalId("1688", "SUP-XYZ")).thenReturn(Optional.empty());

        useCase.createProductManual(r);

        ArgumentCaptor<SupplierEntity> captor = ArgumentCaptor.forClass(SupplierEntity.class);
        verify(supplierRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Proveedor SUP-XYZ");
    }

    @Test
    void unIdExternoDeProveedorDemasiadoLargoSeRecortaALoQueAdmiteLaColumna() {
        BulkProductDtoIn r = validRow();
        r.setSupplierExternalId("X".repeat(150));
        when(supplierRepository.findBySourceAndExternalId(eq("1688"), anyString())).thenReturn(Optional.empty());

        useCase.createProductManual(r);

        ArgumentCaptor<SupplierEntity> captor = ArgumentCaptor.forClass(SupplierEntity.class);
        verify(supplierRepository).save(captor.capture());
        assertThat(captor.getValue().getExternalId()).hasSize(100);
    }

    @Test
    void unProveedorPorNombreSeCreaEnVezDeReutilizarElPrimeroDeLaLista() {
        // Reutilizar el primero hacía que una camiseta apuntase a la fábrica de zapatos.
        BulkProductDtoIn r = validRow();
        r.setSupplierName("Zapatos Wenzhou");
        when(supplierRepository.findFirstByNameIgnoreCase("Zapatos Wenzhou")).thenReturn(Optional.empty());

        useCase.createProductManual(r);

        ArgumentCaptor<SupplierEntity> captor = ArgumentCaptor.forClass(SupplierEntity.class);
        verify(supplierRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Zapatos Wenzhou");
        assertThat(captor.getValue().getExternalId()).startsWith("NAME-");
        assertThat(capturedIngest().supplierId()).isNotEqualTo(SUPPLIER_ID);
    }

    @Test
    void elFabricanteSirveDeProveedorCuandoNoSeDeclaraElNombreDelProveedor() {
        BulkProductDtoIn r = validRow();
        r.setManufacturer("Guangzhou Textiles");
        SupplierEntity existing = SupplierEntity.builder().name("Guangzhou Textiles").build();
        existing.setId(SUPPLIER_ID);
        when(supplierRepository.findFirstByNameIgnoreCase("Guangzhou Textiles")).thenReturn(Optional.of(existing));

        useCase.createProductManual(r);

        assertThat(capturedIngest().supplierId()).isEqualTo(SUPPLIER_ID);
    }

    @Test
    void sinPistasDeProveedorSeUsaElPrimeroDisponible() {
        useCase.createProductManual(validRow());

        assertThat(capturedIngest().supplierId()).isEqualTo(SUPPLIER_ID);
    }

    @Test
    void sinPistasYSinNingunProveedorDadoDeAltaLaFilaSeRechaza() {
        when(supplierRepository.findAll()).thenReturn(List.of());
        BulkProductDtoIn r = validRow();

        assertThatThrownBy(() -> useCase.createProductManual(r)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("No hay proveedores");
    }

    /* ============ contenido canónico y traducciones ============ */

    @Test
    void unaFilaSinTituloEnNingunIdiomaSeRechaza() {
        BulkProductDtoIn r = validRow();
        r.setTitleEs(null);

        assertThatThrownBy(() -> useCase.createProductManual(r)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Falta el título");
    }

    @Test
    void elTituloEspanolSeRellenaDesdeElMapaDeTraduccionesSiSoloVieneAlli() {
        BulkProductDtoIn r = validRow();
        r.setTitleEs(null);
        r.setTranslations(Map.of("es", new BulkProductDtoIn.BulkTranslation("Camisa de lino", null, "Descripción")));

        useCase.createProductManual(r);

        assertThat(capturedSpanishTitle()).isEqualTo("Camisa de lino");
    }

    @Test
    void sinEspanolElPrimerIdiomaConTituloSirveDeCanonico() {
        // El alta exige titleEs; sin este respaldo una fila perfectamente válida en francés se rechazaría.
        BulkProductDtoIn r = validRow();
        r.setTitleEs(null);
        r.setTranslations(Map.of("fr", new BulkProductDtoIn.BulkTranslation("Chemise en lin", null, "Description")));

        useCase.createProductManual(r);

        assertThat(capturedSpanishTitle()).isEqualTo("Chemise en lin");
    }

    @Test
    void unaFilaQueSoloVieneEnChinoSeAceptaYEsElChinoElQueHaceDeCanonico() {
        // Un volcado de 1688 con translations {"zh": {...}} y nada más tiene que ENTRAR: rechazarlo por
        // «falta el título» dejaría fuera un producto perfectamente vendible. El chino sigue siendo el
        // título de trabajo del importador —de él salen el identificador externo, el nombre de las
        // variantes y el slug degradado— y el canónico del producto; lo que ya no hace es acabar guardado
        // como traducción española, inglesa y portuguesa (eso lo cubre CatalogFillWriterTest).
        BulkProductDtoIn r = validRow();
        r.setTitleEs(null);
        r.setTranslations(Map.of("zh",
                new BulkProductDtoIn.BulkTranslation("破洞牛仔裤男春季2025浅色修身弹力九分裤", null, "弹力牛仔裤")));

        useCase.createProductManual(r);

        assertThat(capturedSpanishTitle()).isEqualTo("破洞牛仔裤男春季2025浅色修身弹力九分裤");
        assertThat(capturedIngest().titleZh()).isEqualTo("破洞牛仔裤男春季2025浅色修身弹力九分裤");
    }

    @Test
    void elTituloExplicitoDeLaFilaGanaAlDelMapaDeTraducciones() {
        BulkProductDtoIn r = validRow();
        r.setTranslations(Map.of("es", new BulkProductDtoIn.BulkTranslation("Otro título", null, null)));

        useCase.createProductManual(r);

        assertThat(capturedSpanishTitle()).isEqualTo("Camiseta de algodón");
    }

    /* ============ campos derivados de la fila ============ */

    @Test
    void laUrlDeOrigenSeDerivaDelIdExternoCuandoLaFilaNoLaTrae() {
        // El operador la usa para ir a comprar el producto al proveedor: sin ella no puede tramitar.
        useCase.createProductManual(validRow());

        assertThat(capturedIngest().sourceUrl()).isEqualTo("https://detail.1688.com/offer/OFFER-1.html");
    }

    @Test
    void laUrlDeOrigenDeclaradaEnLaFilaSeRespeta() {
        BulkProductDtoIn r = validRow();
        r.setSourceUrl("  https://detail.1688.com/offer/otra.html  ");

        useCase.createProductManual(r);

        assertThat(capturedIngest().sourceUrl()).isEqualTo("https://detail.1688.com/offer/otra.html");
    }

    @Test
    void elPrecioSeTomaDelTramoMasBaratoCuandoNoVieneExplicito() {
        BulkProductDtoIn r = validRow();
        r.setPrice(null);
        r.setTieredPricing(List.of(new BulkProductDtoIn.BulkTier(1, 9, new BigDecimal("15.00"), null),
                new BulkProductDtoIn.BulkTier(10, null, new BigDecimal("11.00"), null)));

        useCase.createProductManual(r);

        assertThat(capturedIngest().basePrice()).isEqualByComparingTo("11.00");
    }

    @Test
    void laPrimeraImagenDeLaFilaEsLaPrincipalYElRestoGaleria() {
        BulkProductDtoIn r = validRow();
        r.setImageUrls(List.of("a.jpg", "b.jpg", "c.jpg"));

        useCase.createProductManual(r);

        List<IngestImage> images = capturedIngest().images();
        assertThat(images).hasSize(3);
        assertThat(images.get(0).role()).isEqualTo("MAIN");
        assertThat(images.get(1).role()).isEqualTo("GALLERY");
        // El orden es el del proveedor y la ficha lo respeta.
        assertThat(images.get(2).sourceUrl()).isEqualTo("c.jpg");
    }

    /* ============ upsert idempotente ============ */

    @Test
    void reimportarConIdExternoLimpiaLasFilasHijasSinBorrarElProducto() {
        // Es lo que hace que el id no cambie y se conserven favoritos y pedidos (a diferencia de un
        // borrado + alta).
        ProductEntity existing = ProductEntity.builder().externalId("OFFER-1").build();
        existing.setId(PRODUCT_ID);
        when(productJpaRepository.findFirstByExternalId("OFFER-1")).thenReturn(Optional.of(existing));

        useCase.createProductManual(validRow());

        verify(jdbcTemplate).update("DELETE FROM product_review WHERE product_id = ?", PRODUCT_ID);
        verify(jdbcTemplate).update("DELETE FROM product_price_tier WHERE product_id = ?", PRODUCT_ID);
        verify(productJpaRepository, never()).delete(any(ProductEntity.class));
    }

    @Test
    void sinIdExternoDeclaradoNoSeBorranFilasHijasDeNadie() {
        // El identificador se genera con la marca de tiempo: no puede coincidir con ningún producto.
        BulkProductDtoIn r = validRow();
        r.setExternalId(null);

        useCase.createProductManual(r);

        verify(jdbcTemplate, never()).update(anyString(), any(UUID.class));
    }

    /* ============ estado, reseñas, atributos y ficha técnica ============ */

    @Test
    void soloElEstadoDraftDegradaElProductoQueElEscritorYaPublico() {
        BulkProductDtoIn r = validRow();
        r.setStatus(" draft ");

        useCase.createProductManual(r);

        assertThat(managed.getStatus()).isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void unEstadoDistintoDeDraftDejaElProductoPublicado() {
        BulkProductDtoIn r = validRow();
        r.setStatus("ACTIVE");
        managed.setStatus(ProductStatus.ACTIVE);

        useCase.createProductManual(r);

        assertThat(managed.getStatus()).isEqualTo(ProductStatus.ACTIVE);
    }

    @Test
    void unaResenaSinCuerpoNiTituloSeDescarta() {
        // Una reseña vacía no le dice nada al comprador y ensucia la media.
        BulkProductDtoIn r = validRow();
        r.setReviews(List.of(new BulkProductDtoIn.BulkReview("Ana", "ES", 5, null, null, null, null, null),
                new BulkProductDtoIn.BulkReview("Luis", "ES", 4, null, "Muy buena calidad", null, null, null)));

        useCase.createProductManual(r);

        verify(productReviewJpaRepositoryAdapter, times(1)).save(any(ProductReviewEntity.class));
    }

    @Test
    void laPuntuacionDeLaResenaSeAcotaEntreUnoYCinco() {
        BulkProductDtoIn r = validRow();
        r.setReviews(List.of(new BulkProductDtoIn.BulkReview("Ana", "ES", 9, "T", "Cuerpo", "en", true,
                List.of("calidad", "envío"))));

        useCase.createProductManual(r);

        ArgumentCaptor<ProductReviewEntity> captor = ArgumentCaptor.forClass(ProductReviewEntity.class);
        verify(productReviewJpaRepositoryAdapter).save(captor.capture());
        assertThat(captor.getValue().getRating()).isEqualTo((short) 5);
        assertThat(captor.getValue().getLanguage()).isEqualTo("en");
        assertThat(captor.getValue().getTags()).isEqualTo("calidad,envío");
        // Aunque el fichero de carga diga que la reseña es de compra verificada, NO se marca: no hay
        // ninguna compra en esta tienda detrás de ella. Presentarla como tal está en la lista negra de
        // prácticas desleales de la Directiva Omnibus.
        assertThat(captor.getValue().isVerifiedPurchase()).isFalse();
        assertThat(captor.getValue().getSource()).isEqualTo(ReviewSource.SUPPLIER);
    }

    @Test
    void unaResenaSinAutorNiIdiomaSeFirmaComoAnonimaEnEspanol() {
        BulkProductDtoIn r = validRow();
        r.setReviews(List.of(new BulkProductDtoIn.BulkReview(null, null, null, null, "Cuerpo", null, null, null)));

        useCase.createProductManual(r);

        ArgumentCaptor<ProductReviewEntity> captor = ArgumentCaptor.forClass(ProductReviewEntity.class);
        verify(productReviewJpaRepositoryAdapter).save(captor.capture());
        assertThat(captor.getValue().getAuthorName()).isEqualTo("Anónimo");
        assertThat(captor.getValue().getLanguage()).isEqualTo("es");
        assertThat(captor.getValue().getRating()).isEqualTo((short) 5);
        assertThat(captor.getValue().isApproved()).isTrue();
    }

    @Test
    void losAtributosSeReemplazanEnterosYSeIgnoranLosIncompletos() {
        // Acumularlos dejaría el valor viejo y el nuevo conviviendo, y la faceta devolvería el producto
        // por los dos.
        ProductAttributeEntity old = ProductAttributeEntity.builder().attrKey("material").build();
        when(productAttributeRepository.findByProduct_Id(PRODUCT_ID)).thenReturn(List.of(old));
        BulkProductDtoIn r = validRow();
        r.setAttributes(List.of(new BulkProductDtoIn.BulkAttr("material", "Algodón", " ES "),
                new BulkProductDtoIn.BulkAttr("color", "  ", null),
                new BulkProductDtoIn.BulkAttr(null, "sin clave", null)));

        useCase.createProductManual(r);

        verify(productAttributeRepository).deleteAll(List.of(old));
        ArgumentCaptor<ProductAttributeEntity> captor = ArgumentCaptor.forClass(ProductAttributeEntity.class);
        verify(productAttributeRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getAttrKey()).isEqualTo("material");
        assertThat(captor.getValue().getLocale()).isEqualTo("es");
    }

    @Test
    void laPosicionDeLaFichaTecnicaAvanzaTambienConLasFilasDescartadas() {
        // Así una fila incompleta descartada no reordena las siguientes respecto al fichero original.
        BulkProductDtoIn r = validRow();
        r.setSpecifications(List.of(new BulkProductDtoIn.BulkSpec(null, "Material", "Algodón", null),
                new BulkProductDtoIn.BulkSpec(null, "  ", "descartada", null),
                new BulkProductDtoIn.BulkSpec("EN", "Colour", "Blue", null)));

        useCase.createProductManual(r);

        ArgumentCaptor<ProductSpecificationEntity> captor = ArgumentCaptor
                .forClass(ProductSpecificationEntity.class);
        verify(productSpecificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getPosition()).isZero();
        assertThat(captor.getAllValues().get(0).getLocale()).isEqualTo("es");
        assertThat(captor.getAllValues().get(1).getPosition()).isEqualTo(2);
        // El idioma se normaliza a minúsculas: la consulta de la ficha compara con "=" contra "en" y
        // Postgres distingue mayúsculas, así que guardarlo como "EN" hacía que esas especificaciones no
        // se mostraran y el visitante cayera al respaldo, que mezcla todos los idiomas.
        assertThat(captor.getAllValues().get(1).getLocale()).isEqualTo("en");
    }

    @Test
    void elEnvioElIvaYElRecargoDeLaFilaSeGuardanEnElProducto() {
        useCase.createProductManual(validRow());

        assertThat(managed.getShippingCny()).isEqualByComparingTo("10");
        assertThat(managed.getIvaCny()).isEqualByComparingTo("2");
        // DROP-158: el recargo fijo también se aplica al importar (viaja en el export del bus).
        assertThat(managed.getSurchargeCny()).isEqualByComparingTo("3");
        verify(customsProfileService).applyDefaults(managed, CATEGORY_SLUG);
    }

    /* ============ carga masiva de categorías ============ */

    @Test
    void unaCategoriaSinNombreEnEspanolSeRechazaSinCortarElLote() {
        BulkCategoryDtoIn mala = new BulkCategoryDtoIn("x", null, null, null, null, null, null, null);
        BulkCategoryDtoIn buena = new BulkCategoryDtoIn("y", "Buena", null, null, null, null, 1, null);

        BulkResultDtoOut result = useCase.bulkCreateCategories(List.of(mala, buena));

        assertThat(result.getCreated()).isEqualTo(1);
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0)).startsWith("Fila 1: ").contains("nameEs");
    }

    @Test
    void unPadreDesconocidoTumbaSoloSuFila() {
        BulkCategoryDtoIn r = new BulkCategoryDtoIn("hijo", "Hijo", null, null, null, null, 1, "padre-inexistente");
        when(categoryRepository.findBySlug("padre-inexistente")).thenReturn(Optional.empty());

        BulkResultDtoOut result = useCase.bulkCreateCategories(List.of(r));

        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getErrors().get(0)).contains("padre-inexistente");
    }

    @Test
    void losNombresQueFaltanCaenAlEspanolYLaCategoriaSeCreaPorElProxy() {
        // Ir por el proxy es lo que da a cada categoría su propia transacción: con `this` el lote entero
        // correría sin ninguna.
        BulkCategoryDtoIn r = new BulkCategoryDtoIn("bolsos", "Bolsos", null, null, null, null, 7, null);

        useCase.bulkCreateCategories(List.of(r));

        ArgumentCaptor<IngestCategoryRequest> captor = ArgumentCaptor.forClass(IngestCategoryRequest.class);
        verify(self).upsertCategory(captor.capture());
        IngestCategoryRequest req = captor.getValue();
        assertThat(req.slug()).isEqualTo("bolsos");
        assertThat(req.position()).isEqualTo(7);
        assertThat(req.icon()).isEqualTo("tag");
        assertThat(req.nameTranslations()).containsEntry("es", "Bolsos").containsEntry("en", "Bolsos")
                .containsEntry("pt", "Bolsos");
    }

    @Test
    void sinPosicionDeclaradaLaCategoriaSeColocaAlFinal() {
        when(categoryRepository.count()).thenReturn(12L);
        BulkCategoryDtoIn r = new BulkCategoryDtoIn("bolsos", "Bolsos", null, null, null, null, null, null);

        useCase.bulkCreateCategories(List.of(r));

        ArgumentCaptor<IngestCategoryRequest> captor = ArgumentCaptor.forClass(IngestCategoryRequest.class);
        verify(self).upsertCategory(captor.capture());
        assertThat(captor.getValue().position()).isEqualTo(13);
    }

    /* ============ lotes de borrado / cambio de estado ============ */

    @Test
    void elBorradoEnLoteVaPorElProxyParaQueCadaProductoTengaSuTransaccion() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        CatalogUseCase.BulkOutcome outcome = useCase.bulkDeleteProducts(List.of(a, b));

        assertThat(outcome.succeeded()).isEqualTo(2);
        assertThat(outcome.failed()).isZero();
        verify(self).deleteProduct(a);
        verify(self).deleteProduct(b);
    }

    @Test
    void unProductoQueFallaNoCortaElLoteYSuErrorSaleConSuId() {
        UUID malo = UUID.randomUUID();
        UUID bueno = UUID.randomUUID();
        doThrow(new BusinessException("Tiene pedidos")).when(self).deleteProduct(malo);

        CatalogUseCase.BulkOutcome outcome = useCase.bulkDeleteProducts(List.of(malo, bueno));

        assertThat(outcome.succeeded()).isEqualTo(1);
        assertThat(outcome.errors()).containsExactly(malo + ": Tiene pedidos");
    }

    @Test
    void unaListaNulaDeIdentificadoresNoRompeElLote() {
        CatalogUseCase.BulkOutcome outcome = useCase.bulkDeleteProducts(null);

        assertThat(outcome.succeeded()).isZero();
        assertThat(outcome.errors()).isEmpty();
    }

    @Test
    void elCambioDeEstadoEnLoteAplicaElMismoEstadoATodos() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        useCase.bulkUpdateStatus(List.of(a, b), "PAUSED");

        verify(self).updateStatus(a, "PAUSED");
        verify(self).updateStatus(b, "PAUSED");
    }

    @Test
    void noSePuedeBorrarUnProductoConPedidos() {
        // Borrarlo dejaría líneas de pedido apuntando a la nada y las facturas antiguas sin producto.
        when(productJpaRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(managed));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(UUID.class))).thenReturn(3L);

        assertThatThrownBy(() -> useCase.deleteProduct(PRODUCT_ID)).isInstanceOf(BusinessException.class)
                .hasMessageContaining("Archívalo");
        verify(productJpaRepository, never()).delete(any(ProductEntity.class));
    }

    @Test
    void borrarUnProductoSinPedidosLimpiaSusTablasHijasYLoSacaDelIndice() {
        when(productJpaRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(managed));
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(UUID.class))).thenReturn(0L);

        useCase.deleteProduct(PRODUCT_ID);

        verify(jdbcTemplate).update("DELETE FROM product_review WHERE product_id = ?", PRODUCT_ID);
        verify(productJpaRepository).delete(managed);
        verify(productIndexer).deleteFromIndex(PRODUCT_ID);
    }

    @Test
    void borrarUnProductoInexistenteDevuelveNoEncontrado() {
        UUID desconocido = UUID.randomUUID();
        when(productJpaRepository.findById(desconocido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.deleteProduct(desconocido)).isInstanceOf(NotFoundException.class);
    }

    /* ============ mapeos de 1688 y esquema de atributos ============ */

    @Test
    void elMapeoDeCategoriaDe1688ExigeElIdExterno() {
        assertThatThrownBy(() -> useCase.upsertCategory1688Mapping("  ", "女装", CATEGORY_ID))
                .isInstanceOf(BusinessException.class).hasMessageContaining("external1688Id");
    }

    @Test
    void elMapeoDeCategoriaApuntaAUnaCategoriaQueDebeExistir() {
        UUID desconocida = UUID.randomUUID();
        when(categoryRepository.findById(desconocida)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.upsertCategory1688Mapping("555", "女装", desconocida))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void elMapeoExistenteSeActualizaEnSitioYElNombreEnBlancoSeGuardaNulo() {
        Category1688MappingEntity existing = Category1688MappingEntity.builder().external1688Id("555").build();
        existing.setId(UUID.randomUUID());
        when(categoryRepository.findById(CATEGORY_ID)).thenReturn(Optional.of(category));
        when(category1688MappingRepository.findByExternal1688Id("555")).thenReturn(Optional.of(existing));
        when(category1688MappingRepository.save(any(Category1688MappingEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        useCase.upsertCategory1688Mapping(" 555 ", "   ", CATEGORY_ID);

        assertThat(existing.getExternal1688Name()).isNull();
        assertThat(existing.getCategory()).isSameAs(category);
    }

    @Test
    void elEsquemaDeAtributosExigeLaClave() {
        assertThatThrownBy(() -> useCase.upsertCategoryAttributeSchema(CATEGORY_ID, "  ", "Material", true, 0))
                .isInstanceOf(BusinessException.class).hasMessageContaining("attrKey");
    }

    @Test
    void elEsquemaDeAtributosApuntaAUnaCategoriaQueDebeExistir() {
        UUID desconocida = UUID.randomUUID();
        when(categoryRepository.findById(desconocida)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.upsertCategoryAttributeSchema(desconocida, "material", null, true, 0))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void elNombreVisibleDelMapeoPrefiereLaTraduccionEspanolaYLuegoElChino() {
        CategoryEntity conEs = CategoryEntity.builder().slug("a").nameZh("女装").build();
        conEs.getTranslations()
                .add(CategoryTranslationEntity.builder().category(conEs).language("es").name("Mujer").build());
        CategoryEntity soloZh = CategoryEntity.builder().slug("b").nameZh("鞋").build();
        CategoryEntity soloSlug = CategoryEntity.builder().slug("c").build();
        when(category1688MappingRepository.findAll())
                .thenReturn(List.of(Category1688MappingEntity.builder().category(conEs).build(),
                        Category1688MappingEntity.builder().category(soloZh).build(),
                        Category1688MappingEntity.builder().category(soloSlug).build(),
                        Category1688MappingEntity.builder().build()));

        List<String> names = useCase.listCategory1688Mappings().stream().map(m -> m.categoryName()).toList();

        assertThat(names).containsExactly("Mujer", "鞋", "c", null);
    }

    /* ============ exportación ============ */

    /** Consulta de exportación devuelta por el EntityManager doble, encadenable como la real. */
    @SuppressWarnings("unchecked")
    private TypedQuery<ProductEntity> stubExportQuery() {
        TypedQuery<ProductEntity> query = mock(TypedQuery.class);
        when(em.createQuery(anyString(), eq(ProductEntity.class))).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.setFirstResult(anyInt())).thenReturn(query);
        when(query.setMaxResults(anyInt())).thenReturn(query);
        when(query.getResultList()).thenReturn(new ArrayList<>());
        return query;
    }

    @Test
    void exportarDesdeCeroNoPideUnDesplazamientoNegativo() {
        // setFirstResult(-1) revienta: el rango del operador empieza en la fila 1, no en la 0.
        TypedQuery<ProductEntity> query = stubExportQuery();

        useCase.exportProducts(0, 10, null, null);

        verify(query).setFirstResult(0);
        verify(query).setMaxResults(10);
    }

    @Test
    void unRangoInvertidoDeExportacionDevuelveUnaSolaFilaEnVezDeUnLimiteNegativo() {
        // "de la 50 a la 10" no puede traducirse en un maxResults negativo, que la consulta rechaza.
        TypedQuery<ProductEntity> query = stubExportQuery();

        useCase.exportProducts(50, 10, null, null);

        verify(query).setFirstResult(49);
        verify(query).setMaxResults(1);
    }
}
