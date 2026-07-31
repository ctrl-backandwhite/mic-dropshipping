package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper;
import com.nexaplatform.dropshipping.application.service.CustomsProfileService;
import com.nexaplatform.dropshipping.application.usecase.impl.CatalogUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.infrastructure.integration.search.CategoryIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductIndexer;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ImageMirrorService;
import com.nexaplatform.dropshipping.infrastructure.integration.storage.ObjectStorageService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductTranslationEntity;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Área de LECTURAS y EDICIÓN DE ADMIN de {@link CatalogUseCaseImpl}: por qué consulta el listado del
 * panel, qué pasa cuando la ficha no existe, y qué toca (y qué NO toca) la edición rápida.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov02CatalogAdminReadTest {

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

    @InjectMocks
    CatalogUseCaseImpl useCase;

    private ProductEntity producto;

    @BeforeEach
    void setUp() {
        producto = ProductEntity.builder().source("1688").externalId("OFFER-1").titleZh("原始标题")
                .slug("zapatos-offer-1").status(ProductStatus.DRAFT).moq(1).basePrice(new BigDecimal("10"))
                .currency("CNY").build();
        producto.setId(UUID.randomUUID());
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(any())).thenReturn(List.of());
    }

    private static Page<ProductEntity> pagina(ProductEntity... items) {
        return new PageImpl<>(List.of(items));
    }

    /* ============ listado de admin ============ */

    @Test
    void conTextoDeBusquedaSeConsultaTodoElCatalogoNoSoloLaPaginaActual() {
        // La caja de búsqueda del admin debe encontrar el producto esté en la página que esté y en
        // cualquier idioma; filtrar en cliente solo miraría las 20 filas visibles.
        when(productJpaRepository.searchAdmin(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(pagina(producto));

        Page<ProductSummaryView> page = useCase.listProductsForAdmin("ACTIVE", null, "  Bailarinas  ", 0, 20,
                "es", null, null);

        assertThat(page.getTotalElements()).isEqualTo(1);
        verify(productJpaRepository).searchAdmin(eq(ProductStatus.ACTIVE), isNull(), eq("bailarinas"), isNull(),
                any(Pageable.class));
        // El listado se traduce al idioma pedido: el admin en español no puede ver títulos en chino.
        verify(productMapper).toSummary(producto, "es");
    }

    @Test
    void elFiltroDeVerificadosUsaLaBusquedaAunqueNoHayaTexto() {
        // needle "" (no nulo) evita el error de tipo de Postgres al bindear null en el LIKE.
        when(productJpaRepository.searchAdmin(any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(pagina(producto));

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", null, Boolean.FALSE);

        verify(productJpaRepository).searchAdmin(isNull(), isNull(), eq(""), eq(Boolean.FALSE),
                any(Pageable.class));
    }

    @Test
    void sinTextoNiCategoriaSeListaTodoElCatalogo() {
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));

        useCase.listProductsForAdmin("ALL", null, "", 0, 20, "es", null, null);

        verify(productJpaRepository).findAll(any(Pageable.class));
        verify(productJpaRepository, never()).searchAdmin(any(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void unEstadoBasuraNoFiltraNadaEnVezDeReventar() {
        // El front serializa a veces undefined/null como texto; el listado no puede devolver 500 por eso.
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));

        for (String estado : new String[] {"undefined", "null", "  ", "INVENTADO"}) {
            useCase.listProductsForAdmin(estado, null, null, 0, 20, "es", null, null);
        }

        verify(productJpaRepository, times(4)).findAll(any(Pageable.class));
        verify(productJpaRepository, never()).findByStatus(any(), any(Pageable.class));
    }

    @Test
    void conCategoriaYSinEstadoSeListaLaCategoriaEntera() {
        UUID categoria = UUID.randomUUID();
        when(productJpaRepository.findByCategoryId(eq(categoria), any(Pageable.class))).thenReturn(pagina(producto));

        useCase.listProductsForAdmin("ALL", categoria, null, 0, 20, "es", null, null);

        verify(productJpaRepository).findByCategoryId(eq(categoria), any(Pageable.class));
    }

    @Test
    void conCategoriaYEstadoSeCombinanAmbosFiltros() {
        UUID categoria = UUID.randomUUID();
        when(productJpaRepository.findByCategoryIdAndStatus(eq(categoria), eq(ProductStatus.PAUSED),
                any(Pageable.class))).thenReturn(pagina(producto));

        useCase.listProductsForAdmin("paused", categoria, null, 0, 20, "es", null, null);

        verify(productJpaRepository).findByCategoryIdAndStatus(eq(categoria), eq(ProductStatus.PAUSED),
                any(Pageable.class));
    }

    @Test
    void elTamanoDePaginaSeCapaADoscientos() {
        // Sin el tope, un `size` enorme del cliente traería el catálogo entero a memoria.
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        useCase.listProductsForAdmin(null, null, null, 0, 100000, "es", null, null);

        verify(productJpaRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void elOrdenPorPrecioUsaElPrecioBasePersistido() {
        // El margen es multiplicativo: ordenar por el precio CNY da el mismo orden que por el de venta.
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", "price_desc", null);

        verify(productJpaRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("basePrice"))
                .isNotNull()
                .extracting(Sort.Order::getDirection).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void unOrdenDesconocidoDejaElListadoSinOrdenar() {
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", "por_lo_que_sea", null);

        verify(productJpaRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().isSorted()).isFalse();
    }

    @Test
    void elOrdenPorAntiguedadUsaLaFechaDeIngesta() {
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", "oldest", null);

        verify(productJpaRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("ingestedAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection).isEqualTo(Sort.Direction.ASC);
    }

    @Test
    void listarPorEstadoConcretoNoTraeElCatalogoCompleto() {
        when(productJpaRepository.findByStatus(eq(ProductStatus.ARCHIVED), any(Pageable.class)))
                .thenReturn(pagina(producto));

        useCase.listProducts(ProductStatus.ARCHIVED, PageRequest.of(0, 10), "es");

        verify(productJpaRepository).findByStatus(eq(ProductStatus.ARCHIVED), any(Pageable.class));
        verify(productJpaRepository, never()).findAll(any(Pageable.class));
    }

    /* ============ lecturas de ficha ============ */

    @Test
    void unProductoSinIdOInexistenteNoTieneResumen() {
        // El resumen se usa en listados heterogéneos: devolver null es preferible a romper la respuesta.
        assertThat(useCase.toSummaryView(null, "es")).isNull();
        assertThat(useCase.toSummaryView(Product.builder().build(), "es")).isNull();

        Product huerfano = Product.builder().id(UUID.randomUUID()).build();
        when(productJpaRepository.findById(huerfano.getId())).thenReturn(Optional.empty());
        assertThat(useCase.toSummaryView(huerfano, "es")).isNull();
    }

    @Test
    void elModeloDeProductoPorIdFallaSiNoExiste() {
        UUID id = UUID.randomUUID();
        when(productRepository.getById(id)).thenReturn(null);

        assertThatThrownBy(() -> useCase.getProductModelById(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void elModeloDeProductoPorSlugFallaSiNoExiste() {
        when(productRepository.findBySlug("no-existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getProductModelBySlug("no-existe")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void laFichaPorSlugInexistenteDevuelveNoEncontrado() {
        when(productJpaRepository.findWithDetailsBySlug("no-existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getProductBySlug("no-existe", "es"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void laFichaPorIdInexistenteDevuelveNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(productJpaRepository.findWithDetailsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getProductById(id, "es")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void laFichaPorSlugCargaLasColeccionesDentroDeLaTransaccion() {
        // open-in-view está desactivado: si no se fuerzan aquí, el mapeo revienta con LazyInitialization.
        when(productJpaRepository.findWithDetailsBySlug("zapatos-offer-1")).thenReturn(Optional.of(producto));

        useCase.getProductBySlug("zapatos-offer-1", "es");

        verify(priceTierRepository).findByProductIdOrderByMinQtyAsc(producto.getId());
        verify(productMapper).toDetail(eq(producto), eq("es"), any());
    }

    @Test
    void laFichaPorProveedorYCodigoExternoResuelvePrimeroElIdInterno() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "OFFER-1")).thenReturn(Optional.of(producto));
        when(productJpaRepository.findWithDetailsById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.getProductByExternal("1688", "OFFER-1", "es");

        verify(productJpaRepository).findWithDetailsById(producto.getId());
        verify(productMapper).toDetail(eq(producto), eq("es"), any());
    }

    @Test
    void unCodigoExternoDesconocidoDevuelveNoEncontrado() {
        when(productJpaRepository.findBySourceAndExternalId("1688", "NADA")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getProductByExternal("1688", "NADA", "es"))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void losMasVendidosSeLimitanALoPublicadoYALaCategoriaPedida() {
        UUID categoria = UUID.randomUUID();
        when(productJpaRepository.findByCategoryOrderByTrend(eq(categoria), eq(ProductStatus.ACTIVE),
                any(Pageable.class))).thenReturn(pagina(producto));
        when(productJpaRepository.findTopByTrendScore(eq(ProductStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(pagina(producto));

        useCase.listBestsellers(categoria, PageRequest.of(0, 8), "es");
        useCase.listBestsellers(null, PageRequest.of(0, 8), "es");

        verify(productJpaRepository).findByCategoryOrderByTrend(eq(categoria), eq(ProductStatus.ACTIVE),
                any(Pageable.class));
        verify(productJpaRepository).findTopByTrendScore(eq(ProductStatus.ACTIVE), any(Pageable.class));
    }

    /* ============ cambio de estado ============ */

    @Test
    void publicarUnProductoGeneraSuSeoYReindexa() {
        producto.getTranslations().add(ProductTranslationEntity.builder().product(producto).language("es")
                .title("Bailarinas planas de mujer").description("Cómodas y ligeras.").build());
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.updateStatus(producto.getId(), "active");

        assertThat(producto.getStatus()).isEqualTo(ProductStatus.ACTIVE);
        // Publicar sin metadatos dejaría la ficha sin título en Google.
        assertThat(producto.getTranslations().get(0).getMetaTitle()).isNotBlank();
        verify(productIndexer).indexProduct(producto.getId());
    }

    @Test
    void despublicarNoGeneraSeo() {
        producto.getTranslations().add(ProductTranslationEntity.builder().product(producto).language("es")
                .title("Bailarinas planas de mujer").build());
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.updateStatus(producto.getId(), ProductStatus.PAUSED);

        assertThat(producto.getStatus()).isEqualTo(ProductStatus.PAUSED);
        assertThat(producto.getTranslations().get(0).getMetaTitle()).isNull();
    }

    @Test
    void cambiarElEstadoDeUnProductoInexistenteFalla() {
        UUID id = UUID.randomUUID();
        when(productJpaRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.updateStatus(id, ProductStatus.ACTIVE))
                .isInstanceOf(NotFoundException.class);
    }

    /* ============ tramos de precio ============ */

    @Test
    void borrarUnTramoDeUnProductoInexistenteFalla() {
        UUID id = UUID.randomUUID();
        when(productJpaRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> useCase.deletePriceTier(id, 10)).isInstanceOf(NotFoundException.class);
        verify(priceTierRepository, never()).deleteByProductIdAndMinQty(any(), anyInt());
    }

    @Test
    void borrarUnTramoQueNoExisteAvisaEnVezDeDarPorHechoElBorrado() {
        // Si devolviera 200 sin borrar nada, el admin creería haber quitado un tramo que sigue cobrando.
        UUID id = UUID.randomUUID();
        when(productJpaRepository.existsById(id)).thenReturn(true);
        when(priceTierRepository.deleteByProductIdAndMinQty(id, 50)).thenReturn(0L);

        assertThatThrownBy(() -> useCase.deletePriceTier(id, 50)).isInstanceOf(NotFoundException.class);
        verify(productIndexer, never()).indexProduct(any());
    }

    @Test
    void borrarUnTramoExistenteReindexaElProducto() {
        UUID id = UUID.randomUUID();
        when(productJpaRepository.existsById(id)).thenReturn(true);
        when(priceTierRepository.deleteByProductIdAndMinQty(id, 50)).thenReturn(1L);

        useCase.deletePriceTier(id, 50);

        verify(productIndexer).indexProduct(id);
    }

    /* ============ edición rápida ============ */

    @Test
    void laEdicionRapidaSoloTocaLosCamposEnviados() {
        // Un null significa "no lo edito": si se aplicara, editar la marca borraría el precio.
        producto.setBrand("Marca vieja");
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder()
                .basePrice(new BigDecimal("25.50")).build(), "es");

        assertThat(producto.getBrand()).isEqualTo("Marca vieja");
        assertThat(producto.getBasePrice()).isEqualByComparingTo("25.50");
        assertThat(producto.getCurrency()).isEqualTo("CNY");
        assertThat(producto.getTranslations()).isEmpty();
    }

    @Test
    void unaMonedaEnBlancoNoBorraLaDelProducto() {
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().currency("   ").moq(6).build(), "es");

        assertThat(producto.getCurrency()).isEqualTo("CNY");
        assertThat(producto.getMoq()).isEqualTo(6);
    }

    @Test
    void laEdicionRapidaNuncaPisaElTituloChinoCanonico() {
        // El título chino es el dato del proveedor y es lo que empareja las reimportaciones.
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder()
                .title("Bailarinas de mujer").build(), "es");

        assertThat(producto.getTitleZh()).isEqualTo("原始标题");
        assertThat(producto.getTranslations()).hasSize(1);
        assertThat(producto.getTranslations().get(0).getLanguage()).isEqualTo("es");
        assertThat(producto.getTranslations().get(0).getTitle()).isEqualTo("Bailarinas de mujer");
        assertThat(producto.getTranslations().get(0).getProvider()).isEqualTo("admin");
    }

    @Test
    void editarUnIdiomaQueYaExisteNoDuplicaLaTraduccion() {
        producto.getTranslations().add(ProductTranslationEntity.builder().product(producto).language("ES")
                .title("Antiguo").provider("bulk").build());
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder()
                .description("Descripción nueva").build(), "es");

        assertThat(producto.getTranslations()).hasSize(1);
        assertThat(producto.getTranslations().get(0).getTitle()).isEqualTo("Antiguo");
        assertThat(producto.getTranslations().get(0).getDescription()).isEqualTo("Descripción nueva");
    }

    @Test
    void unMetadatoSeoEnBlancoSeGuardaComoNuloParaQueSeRegenere() {
        producto.getTranslations().add(ProductTranslationEntity.builder().product(producto).language("es")
                .title("Bailarinas").metaTitle("Viejo meta").metaDescription("Vieja meta").build());
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder()
                .metaTitle("   ").metaDescription("  Meta escrita a mano  ").build(), "es");

        assertThat(producto.getTranslations().get(0).getMetaTitle()).isNull();
        assertThat(producto.getTranslations().get(0).getMetaDescription()).isEqualTo("Meta escrita a mano");
    }

    @Test
    void borrarElVideoPrincipalDejaElProductoSinVideoSiNoQuedaNingunOtro() {
        producto.setVideoUrl("https://cdn/uno.mp4");
        producto.setHasVideo(true);
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().videoUrl("").build(), "es");

        assertThat(producto.getVideoUrl()).isNull();
        assertThat(producto.getHasVideo()).isFalse();
    }

    @Test
    void borrarElVideoPrincipalConSecundariosMantieneElDistintivoDeVideo() {
        producto.setVideoUrl("https://cdn/uno.mp4");
        producto.setVideoUrls(new ArrayList<>(List.of("https://cdn/dos.mp4")));
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().videoUrl("  ").build(), "es");

        assertThat(producto.getVideoUrl()).isNull();
        assertThat(producto.getHasVideo()).isTrue();
    }

    @Test
    void marcarUnProductoComoVerificadoSePersiste() {
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().verified(true).build(), "es");

        assertThat(producto.getVerified()).isTrue();
    }

    @Test
    void reasignarLaCategoriaExigeQueLaCategoriaExista() {
        UUID categoria = UUID.randomUUID();
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(categoryRepository.findById(categoria)).thenReturn(Optional.empty());
        AdminProductQuickEditDtoIn req = AdminProductQuickEditDtoIn.builder().categoryId(categoria).build();
        UUID id = producto.getId();

        assertThatThrownBy(() -> useCase.quickEdit(id, req, "es")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void reasignarLaCategoriaLaAplicaCuandoExiste() {
        UUID categoria = UUID.randomUUID();
        CategoryEntity destino = CategoryEntity.builder().slug("moda-mujer").build();
        destino.setId(categoria);
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(categoryRepository.findById(categoria)).thenReturn(Optional.of(destino));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().categoryId(categoria).build(), "es");

        assertThat(producto.getCategory()).isSameAs(destino);
        verify(productIndexer).indexProduct(producto.getId());
    }

    @Test
    void laEdicionRapidaDeUnProductoInexistenteFalla() {
        UUID id = UUID.randomUUID();
        when(productJpaRepository.findById(id)).thenReturn(Optional.empty());
        AdminProductQuickEditDtoIn req = AdminProductQuickEditDtoIn.builder().brand("X").build();

        assertThatThrownBy(() -> useCase.quickEdit(id, req, "es")).isInstanceOf(NotFoundException.class);
    }

    /* ============ duplicado ============ */

    @Test
    void duplicarNaceComoBorradorYConCodigoExternoPropio() {
        // Nacer ACTIVE publicaría una copia sin revisar; y repetir el external_id chocaría con el original.
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);

        useCase.duplicateProduct(producto.getId(), "es");

        verify(productJpaRepository, atLeastOnce()).save(captor.capture());
        ProductEntity copia = captor.getAllValues().get(0);
        assertThat(copia.getStatus()).isEqualTo(ProductStatus.DRAFT);
        assertThat(copia.getExternalId()).startsWith("OFFER-1-COPY-").isNotEqualTo("OFFER-1");
        assertThat(copia.getTitleZh()).isEqualTo("原始标题 (copy)");
    }

    @Test
    void duplicarUnCodigoExternoLargoNoDesbordaLaColumna() {
        // external_id es varchar(120): sin el capado, duplicar una carga masiva daba error 500.
        producto.setExternalId("B".repeat(120));
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);

        useCase.duplicateProduct(producto.getId(), "es");

        verify(productJpaRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getExternalId()).hasSizeLessThanOrEqualTo(120);
    }

    @Test
    void duplicarArrastraLasTraduccionesMarcadasComoCopia() {
        producto.getTranslations().add(ProductTranslationEntity.builder().product(producto).language("es")
                .title("Bailarinas").shortDescription("Ligeras").build());
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(productJpaRepository.save(any(ProductEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);

        useCase.duplicateProduct(producto.getId(), "es");

        verify(productJpaRepository, atLeastOnce()).save(captor.capture());
        ProductEntity copia = captor.getAllValues().get(captor.getAllValues().size() - 1);
        assertThat(copia.getTranslations()).hasSize(1);
        assertThat(copia.getTranslations().get(0).getTitle()).isEqualTo("Bailarinas (copy)");
        assertThat(copia.getTranslations().get(0).getProvider()).isEqualTo("admin-duplicate");
    }
}
