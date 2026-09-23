package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.in.AdminProductQuickEditDtoIn;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontMapper;
import com.nexaplatform.dropshipping.api.mapper.ProductBulkExportMapper;
import com.nexaplatform.dropshipping.application.service.CustomsProfileService;
import com.nexaplatform.dropshipping.application.usecase.impl.CatalogUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.BusAnuncioEstado;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.infrastructure.integration.bus.CatalogoBusService;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    /**
     * El bus de integración está apagado en las pruebas: sin él, publicar no forma parte de lo que
     * se está comprobando aquí. Hace falta declararlo igualmente porque Mockito solo inyecta los
     * colaboradores que se le nombran, y el que falta llega como null.
     */
    @Mock
    private ObjectProvider<CatalogoBusService> busCatalogo;

    /** El bus en sí, para comprobar que la petición NO publica nada mientras responde. */
    @Mock
    private CatalogoBusService bus;

    @InjectMocks
    CatalogUseCaseImpl useCase;

    private ProductEntity producto;

    @BeforeEach
    void setUp() {
        producto = ProductEntity.builder().source("1688").externalId("OFFER-1").titleZh("原始标题").slug("zapatos-offer-1")
                .status(ProductStatus.DRAFT).moq(1).basePrice(new BigDecimal("10")).currency("CNY").build();
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
        when(productJpaRepository.searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class))).thenReturn(pagina(producto));

        Page<ProductSummaryView> page = useCase.listProductsForAdmin("ACTIVE", null, "  Bailarinas  ", 0, 20, "es",
                null, null, null, null, null, null);

        assertThat(page.getTotalElements()).isEqualTo(1);
        verify(productJpaRepository).searchAdmin(eq(ProductStatus.ACTIVE), isNull(), eq("bailarinas"), isNull(),
                eq("es"), eq(false), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
        // El listado se traduce al idioma pedido: el admin en español no puede ver títulos en chino.
        verify(productMapper).toSummary(producto, "es");
    }

    @Test
    void elFiltroDeVerificadosUsaLaBusquedaAunqueNoHayaTexto() {
        // needle "" (no nulo) evita el error de tipo de Postgres al bindear null en el LIKE.
        when(productJpaRepository.searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class))).thenReturn(pagina(producto));

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", null, Boolean.FALSE, null, null, null, null);

        verify(productJpaRepository).searchAdmin(isNull(), isNull(), eq(""), eq(Boolean.FALSE), eq("es"), eq(false),
                isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    void sinTextoNiCategoriaSeListaTodoElCatalogo() {
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));

        useCase.listProductsForAdmin("ALL", null, "", 0, 20, "es", null, null, null, null, null, null);

        verify(productJpaRepository).findAll(any(Pageable.class));
        verify(productJpaRepository, never()).searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(), any(),
                any(), any(), any(), any(), any(), any(Pageable.class));
    }

    @Test
    void unEstadoBasuraNoFiltraNadaEnVezDeReventar() {
        // El front serializa a veces undefined/null como texto; el listado no puede devolver 500 por eso.
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));

        for (String estado : new String[]{"undefined", "null", "  ", "INVENTADO"}) {
            useCase.listProductsForAdmin(estado, null, null, 0, 20, "es", null, null, null, null, null, null);
        }

        verify(productJpaRepository, times(4)).findAll(any(Pageable.class));
        verify(productJpaRepository, never()).findByStatus(any(), any(Pageable.class));
    }

    @Test
    void conCategoriaYSinEstadoSeListaLaCategoriaEntera() {
        UUID categoria = UUID.randomUUID();
        when(productJpaRepository.findByCategoryId(eq(categoria), any(Pageable.class))).thenReturn(pagina(producto));

        useCase.listProductsForAdmin("ALL", categoria, null, 0, 20, "es", null, null, null, null, null, null);

        verify(productJpaRepository).findByCategoryId(eq(categoria), any(Pageable.class));
    }

    @Test
    void conCategoriaYEstadoSeCombinanAmbosFiltros() {
        UUID categoria = UUID.randomUUID();
        when(productJpaRepository.findByCategoryIdAndStatus(eq(categoria), eq(ProductStatus.PAUSED),
                any(Pageable.class))).thenReturn(pagina(producto));

        useCase.listProductsForAdmin("paused", categoria, null, 0, 20, "es", null, null, null, null, null, null);

        verify(productJpaRepository).findByCategoryIdAndStatus(eq(categoria), eq(ProductStatus.PAUSED),
                any(Pageable.class));
    }

    @Test
    void elTamanoDePaginaSeCapaADoscientos() {
        // Sin el tope, un `size` enorme del cliente traería el catálogo entero a memoria.
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        useCase.listProductsForAdmin(null, null, null, 0, 100000, "es", null, null, null, null, null, null);

        verify(productJpaRepository).findAll(captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    void elOrdenPorPrecioUsaElPrecioBasePersistido() {
        // El margen es multiplicativo: ordenar por el precio CNY da el mismo orden que por el de venta.
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", "price_desc", null, null, null, null, null);

        verify(productJpaRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("basePrice")).isNotNull()
                .extracting(Sort.Order::getDirection).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void unOrdenDesconocidoIgualmenteSeOrdenaPorId() {
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", "por_lo_que_sea", null, null, null, null, null);

        verify(productJpaRepository).findAll(captor.capture());
        // Un criterio desconocido NO puede dejar el listado sin ordenar: paginar con LIMIT/OFFSET sin
        // ORDER BY deja el orden a criterio de PostgreSQL, que no garantiza ser el mismo entre dos
        // consultas — el admin se saltaría productos al pasar de página, sin que nada fallara.
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Test
    void elOrdenPorAntiguedadUsaLaFechaDeIngesta() {
        when(productJpaRepository.findAll(any(Pageable.class))).thenReturn(pagina(producto));
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", "oldest", null, null, null, null, null);

        verify(productJpaRepository).findAll(captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("ingestedAt")).isNotNull()
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

        assertThatThrownBy(() -> useCase.getProductBySlug("no-existe", "es")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void laFichaPorIdInexistenteDevuelveNoEncontrado() {
        UUID id = UUID.randomUUID();
        when(productJpaRepository.findWithDetailsById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.getProductById(id, "es")).isInstanceOf(NotFoundException.class);
    }

    /**
     * Un producto que no está publicado no existe para el escaparate por NINGUNA puerta. El listado ya lo
     * escondía y el cobro ya lo rechazaba, pero la ficha por slug —enlace directo, resultado indexado o
     * correo antiguo— se servía entera y con su precio. Además se comprueba que ni siquiera se llega a
     * mapear: la respuesta no puede llevar nada del producto retirado.
     */
    @Test
    void laFichaPorSlugDeUnProductoRetiradoNoSeSirveAQuienNoEsAdmin() {
        // El fixture está en DRAFT, y el mismo criterio vale para PAUSED y ARCHIVED.
        when(productJpaRepository.findWithDetailsBySlug("zapatos-offer-1")).thenReturn(Optional.of(producto));

        assertThatThrownBy(() -> useCase.getProductBySlug("zapatos-offer-1", "es"))
                .isInstanceOf(NotFoundException.class);
        verify(productMapper, never()).toDetail(any(), any(), any());
    }

    @Test
    void laFichaPorSlugCargaLasColeccionesDentroDeLaTransaccion() {
        // open-in-view está desactivado: si no se fuerzan aquí, el mapeo revienta con LazyInitialization.
        // El producto se publica porque la ficha por slug solo se sirve si está ACTIVE (o si pregunta un
        // admin): con el DRAFT del fixture este caso mediría el 404, no la carga de las colecciones.
        producto.setStatus(ProductStatus.ACTIVE);
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
        // Poner a la venta exige los datos con los que se declara en aduana; sin ellos el producto ya no
        // se publica, así que el que se usa aquí tiene que ser vendible de verdad.
        producto.getTranslations().add(ProductTranslationEntity.builder().product(producto).language("en")
                .title("Women's flat ballerinas").build());
        producto.setHsCode("6402990000");
        producto.setWeightGrams(400);
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

        assertThatThrownBy(() -> useCase.updateStatus(id, ProductStatus.ACTIVE)).isInstanceOf(NotFoundException.class);
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

        useCase.quickEdit(producto.getId(),
                AdminProductQuickEditDtoIn.builder().basePrice(new BigDecimal("25.50")).build(), "es");

        assertThat(producto.getBrand()).isEqualTo("Marca vieja");
        assertThat(producto.getBasePrice()).isEqualByComparingTo("25.50");
        assertThat(producto.getCurrency()).isEqualTo("CNY");
        assertThat(producto.getTranslations()).isEmpty();
    }

    @Test
    void laEdicionRapidaCorrigeElEnvioYElIva() {
        // El envío y el IVA se cargan al importar y antes no había forma de corregirlos: el endpoint
        // aceptaba el campo y lo descartaba en silencio, así que 262 productos se quedaron con el
        // valor por defecto del importador y la respuesta 200 hacía creer que se había guardado.
        producto.setShippingCny(new BigDecimal("12.00"));
        producto.setIvaCny(new BigDecimal("3.00"));
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().shippingCny(new BigDecimal("10.00"))
                .ivaCny(new BigDecimal("3.38")).build(), "es");

        assertThat(producto.getShippingCny()).isEqualByComparingTo("10.00");
        assertThat(producto.getIvaCny()).isEqualByComparingTo("3.38");
    }

    @Test
    void unEnvioNuloNoBorraElQueYaTeniaElProducto() {
        // Mismo contrato que el resto de campos: null es "no lo edito", no "ponlo a cero".
        producto.setShippingCny(new BigDecimal("10.00"));
        producto.setIvaCny(new BigDecimal("3.38"));
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().moq(2).build(), "es");

        assertThat(producto.getShippingCny()).isEqualByComparingTo("10.00");
        assertThat(producto.getIvaCny()).isEqualByComparingTo("3.38");
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

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().title("Bailarinas de mujer").build(),
                "es");

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

        useCase.quickEdit(producto.getId(),
                AdminProductQuickEditDtoIn.builder().description("Descripción nueva").build(), "es");

        assertThat(producto.getTranslations()).hasSize(1);
        assertThat(producto.getTranslations().get(0).getTitle()).isEqualTo("Antiguo");
        assertThat(producto.getTranslations().get(0).getDescription()).isEqualTo("Descripción nueva");
    }

    @Test
    void unMetadatoSeoEnBlancoSeGuardaComoNuloParaQueSeRegenere() {
        producto.getTranslations().add(ProductTranslationEntity.builder().product(producto).language("es")
                .title("Bailarinas").metaTitle("Viejo meta").metaDescription("Vieja meta").build());
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().metaTitle("   ")
                .metaDescription("  Meta escrita a mano  ").build(), "es");

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

    /**
     * Certificar solo DEJA LA MARCA; la ficha del evento no se construye en la transacción de la
     * petición.
     *
     * <p>Es el motivo del cambio del 4-sep-2026: antes se exportaba el producto entero aquí dentro
     * —consultas, los ocho idiomas, variantes, imágenes y reseñas, todo a JSON— y marcar un producto
     * tardaba segundos; aplicar un recargo a un lote, eso multiplicado por el número de productos. La
     * marca va en la MISMA transacción a propósito: es lo que impide que un producto certificado se
     * quede sin anunciar si el proceso muere justo después de guardar.
     */
    @Test
    void certificarDejaLaMarcaSinConstruirLaFichaEnLaPeticion() {
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(busCatalogo.getIfAvailable()).thenReturn(bus);

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().verified(true).build(), "es");

        assertThat(producto.getBusEstado()).isEqualTo(BusAnuncioEstado.PENDIENTE);
        verifyNoInteractions(bus);
    }

    /**
     * Descertificar también deja la marca. Si solo se anunciara el alta, un producto retirado aquí
     * seguiría a la venta en el entorno de destino.
     */
    @Test
    void descertificarTambienDejaLaMarcaParaQueLaRetiradaViaje() {
        producto.setVerified(true);
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(busCatalogo.getIfAvailable()).thenReturn(bus);

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().verified(false).build(), "es");

        assertThat(producto.getBusEstado()).isEqualTo(BusAnuncioEstado.PENDIENTE);
    }

    /** Un intento fallido anterior no deja al producto fuera de la cola: al volver a tocarlo, vuelve. */
    @Test
    void volverACertificarRescataUnProductoQueSeHabiaDadoPorPerdido() {
        producto.setBusEstado(BusAnuncioEstado.FALLIDO);
        producto.setBusIntentos(5);
        producto.setBusError("el bus no respondía");
        when(productJpaRepository.findById(producto.getId())).thenReturn(Optional.of(producto));
        when(busCatalogo.getIfAvailable()).thenReturn(bus);

        useCase.quickEdit(producto.getId(), AdminProductQuickEditDtoIn.builder().verified(true).build(), "es");

        assertThat(producto.getBusEstado()).isEqualTo(BusAnuncioEstado.PENDIENTE);
        assertThat(producto.getBusIntentos()).isZero();
        assertThat(producto.getBusError()).isNull();
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

    /**
     * El filtro de precio llega a la CONSULTA, no se resuelve en memoria.
     *
     * <p>La columna «Precio» del panel muestra el COSTE del proveedor convertido a la moneda del
     * administrador, no el precio de venta. Se descubrió filtrando 20-30 y viendo 16,52 € en la tabla:
     * el filtro miraba una cosa y la columna enseñaba otra. Al ser una columna real, se compara en SQL,
     * así que el total y la paginación son exactos y no hay tope de barrido.
     */
    @Test
    @DisplayName("el filtro de coste viaja a la consulta")
    void elFiltroDeCosteViajaALaConsulta() {
        when(productJpaRepository.searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class))).thenReturn(pagina());
        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", null, null, BigDecimal.ZERO, BigDecimal.valueOf(50),
                null, null);

        verify(productJpaRepository).searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(),
                eq(BigDecimal.ZERO), eq(BigDecimal.valueOf(50)), isNull(), isNull(), isNull(), isNull(),
                any(Pageable.class));
    }

    /** Ventas y tendencia son MÍNIMOS: lo que se busca en una tabla es «de aquí para arriba». */
    @Test
    @DisplayName("ventas y tendencia filtran por mínimo")
    void ventasYTendenciaFiltranPorMinimo() {
        when(productJpaRepository.searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class))).thenReturn(pagina());
        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", null, null, null, null, 1000,
                BigDecimal.valueOf(0.5));

        verify(productJpaRepository).searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(), isNull(),
                isNull(), eq(1000), eq(BigDecimal.valueOf(0.5)), isNull(), isNull(), any(Pageable.class));
    }

    /**
     * Con filtros de tabla se pagina NORMAL: no hay barrido en memoria.
     *
     * <p>La primera versión filtraba el precio en memoria sobre un barrido de 5.000, porque se creía que
     * la columna mostraba el precio de venta —que no es una columna y no se puede consultar—. Resultó
     * mostrar el COSTE, que sí lo es, así que los cuatro filtros bajaron a la consulta. Esta prueba
     * defiende esa mejora: si alguien volviera a resolverlos en memoria, el total dejaría de ser exacto
     * y aparecería un tope silencioso a partir del cual el filtro deja de ver productos.
     */
    @Test
    @DisplayName("con filtros de tabla se pagina normal, sin barrido en memoria")
    void conFiltrosDeTablaSePaginaNormal() {
        when(productJpaRepository.searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), any(Pageable.class))).thenReturn(pagina());

        useCase.listProductsForAdmin(null, null, null, 0, 20, "es", null, null, null, null, 1, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productJpaRepository).searchAdmin(any(), any(), any(), any(), anyString(), anyBoolean(), any(), any(),
                any(), any(), any(), any(), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
    }
}
