package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.search.ProductSearchService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CustomsDeclarationGroupEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CustomsDeclarationGroupRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El texto libre del escaparate resuelto por el buscador.
 *
 * <p>Lo que se protege: que la búsqueda use el motor cuando está disponible, que respete el orden de
 * relevancia que éste devuelve, que el usuario pueda imponer su propio orden, y —sobre todo— que si el
 * buscador no responde el comprador siga viendo productos en vez de una página vacía.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogStorefrontSearchTest {

    @Mock
    ProductRepository productRepository;
    @Mock
    CategoryRepository categoryRepository;
    @Mock
    SupplierRepository supplierRepository;
    @Mock
    SupplierSearchService supplierSearchService;
    @Mock
    ProductVariantRepository variantRepository;
    @Mock
    ProductMapper productMapper;
    @Mock
    PricingService pricingService;
    @Mock
    PromotionService promotionService;
    @Mock
    ProductSearchService productSearchService;
    @Mock
    CustomsDeclarationGroupRepository declarationGroupRepository;

    @InjectMocks
    CatalogStorefrontReadService service;

    private final Map<UUID, String> nombres = new java.util.HashMap<>();

    @BeforeEach
    void sinPromocionActiva() {
        when(promotionService.reachFilter(any())).thenReturn(Optional.empty());
        when(productMapper.toSummary(any(), anyString()))
                .thenAnswer(inv -> resumen(inv.getArgument(0), BigDecimal.ONE));
    }

    /** Con el buscador disponible manda él: el SQL solo materializa los identificadores que devuelve. */
    @Test
    void elTextoLibreLoResuelveElBuscadorYNoElBarridoSql() {
        ProductEntity botas = producto("botas");
        when(productSearchService.searchRelevantIds("botas", "es")).thenReturn(Optional.of(List.of(botas.getId())));
        when(productRepository.searchStorefrontByIds(eq(ProductStatus.ACTIVE), eq(List.of(botas.getId())), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(botas));

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros("botas"), null);

        assertThat(pagina.totalElements()).isEqualTo(1);
        verify(productRepository, never()).searchStorefront(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class));
    }

    /** El orden del buscador ES la relevancia: la base de datos devuelve las filas en cualquier orden. */
    @Test
    void seRespetaElOrdenDeRelevanciaAunqueLaBaseDeDatosDevuelvaOtro() {
        ProductEntity primera = producto("mas-relevante");
        ProductEntity segunda = producto("menos-relevante");
        when(productSearchService.searchRelevantIds("botas", "es"))
                .thenReturn(Optional.of(List.of(primera.getId(), segunda.getId())));
        when(productRepository.searchStorefrontByIds(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(List.of(segunda, primera));

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros("botas"), null);

        assertThat(pagina.items()).extracting(ProductSummaryView::slug)
                .containsExactly("mas-relevante", "menos-relevante");
    }

    /** Si el usuario elige "precio más bajo", su elección gana a la relevancia. */
    @Test
    void elOrdenElegidoPorElUsuarioGanaALaRelevancia() {
        ProductEntity caro = producto("caro");
        ProductEntity barato = producto("barato");
        when(productSearchService.searchRelevantIds("botas", "es"))
                .thenReturn(Optional.of(List.of(caro.getId(), barato.getId())));
        when(productRepository.searchStorefrontByIds(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(List.of(caro, barato));
        doReturn(resumen(caro, new BigDecimal("50"))).when(productMapper).toSummary(eq(caro), anyString());
        doReturn(resumen(barato, new BigDecimal("5"))).when(productMapper).toSummary(eq(barato), anyString());

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros("botas"), "price_asc");

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("barato", "caro");
    }

    /**
     * El precio por el que se ordena es el que VE el usuario, no el coste en CNY: entre uno y otro median
     * el margen (variable por producto) y la conversión de divisa, así que el orden no es el mismo.
     */
    @Test
    void elOrdenPorPrecioUsaElPrecioMostradoNoElCoste() {
        ProductEntity costeBajo = producto("coste-bajo");
        ProductEntity costeAlto = producto("coste-alto");
        costeBajo.setBasePrice(new BigDecimal("10"));
        costeAlto.setBasePrice(new BigDecimal("90"));
        when(productSearchService.searchRelevantIds("botas", "es"))
                .thenReturn(Optional.of(List.of(costeBajo.getId(), costeAlto.getId())));
        when(productRepository.searchStorefrontByIds(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any())).thenReturn(List.of(costeBajo, costeAlto));
        // El del coste MÁS BAJO se vende MÁS CARO (margen distinto) → ordenar por coste daría el orden inverso.
        doReturn(resumen(costeBajo, new BigDecimal("99"))).when(productMapper).toSummary(eq(costeBajo), anyString());
        doReturn(resumen(costeAlto, new BigDecimal("20"))).when(productMapper).toSummary(eq(costeAlto), anyString());

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros("botas"), "price_asc");

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("coste-alto", "coste-bajo");
    }

    /** Buscador caído ⇒ se busca por SQL. La tienda nunca se queda sin catálogo por un fallo del índice. */
    @Test
    void siElBuscadorNoRespondeSeBuscaPorSql() {
        ProductEntity botas = producto("botas");
        when(productSearchService.searchRelevantIds("botas", "es")).thenReturn(Optional.empty());
        when(productRepository.searchStorefront(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                        .thenReturn(new PageImpl<>(List.of(botas)));

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros("botas"), null);

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("botas");
    }

    /** El buscador responde "no hay nada": es una respuesta, no un fallo — no se reintenta por SQL. */
    @Test
    void sinResultadosNoSeReintentaPorSql() {
        when(productSearchService.searchRelevantIds("zzz", "es")).thenReturn(Optional.of(List.of()));

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros("zzz"), null);

        assertThat(pagina.totalElements()).isZero();
        assertThat(pagina.items()).isEmpty();
        verify(productRepository, never()).searchStorefront(any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class));
    }

    /** Sin texto no se molesta al buscador: navegar el catálogo es cosa del SQL. */
    @Test
    void sinTextoNoSeUsaElBuscador() {
        when(productRepository.searchStorefront(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                        .thenReturn(new PageImpl<>(List.of(producto("cualquiera"))));

        service.productListFull(0, 20, "es", ProductListFilters.none(), null);

        verify(productSearchService, never()).searchRelevantIds(any(), any());
    }

    /**
     * El filtro «ver los que no suman arancel» se resuelve a la TERNA del grupo, no a una columna del
     * producto: es la terna la que hace que dos productos se declaren igual y la aduana los cuente como una
     * sola línea.
     */
    @Test
    void elFiltroPorGrupoDeDeclaracionListaSoloLosDeSuTerna() {
        ProductEntity vestido = producto("vestido");
        UUID grupo = UUID.randomUUID();
        when(declarationGroupRepository.findById(grupo)).thenReturn(Optional.of(CustomsDeclarationGroupEntity
                .builder().id(grupo).hs6("620443").material("POLYESTER").usageCode("DRESS").build()));
        when(productRepository.idsForCustomsTerna(ProductStatus.ACTIVE, "620443", "POLYESTER", "DRESS"))
                .thenReturn(List.of(vestido.getId()));
        when(productRepository.searchStorefrontByIds(eq(ProductStatus.ACTIVE), eq(List.of(vestido.getId())), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(vestido));

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", porGrupo(grupo), null);

        assertThat(pagina.totalElements()).isEqualTo(1);
    }

    @Test
    void unGrupoQueNoExisteDevuelveLaPaginaVaciaYNoElCatalogoEntero() {
        // Es la diferencia entre «no hay nada que cumpla esto» y «toma, todo»: lo segundo enseñaría bajo la
        // etiqueta «no suman arancel» productos que sí lo suman.
        UUID grupo = UUID.randomUUID();
        when(declarationGroupRepository.findById(grupo)).thenReturn(Optional.empty());

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", porGrupo(grupo), null);

        assertThat(pagina.items()).isEmpty();
        assertThat(pagina.totalElements()).isZero();
    }

    @Test
    void conTextoYGrupoSoloQuedaLoQueEstaEnLOSDOS() {
        // El buscador manda en QUÉ casa; el grupo manda en qué se puede prometer. Un producto que casa con
        // el texto pero no comparte terna abriría línea nueva en la aduana y no puede salir bajo la etiqueta.
        ProductEntity delGrupo = producto("vestido-azul");
        ProductEntity fueraDelGrupo = producto("camiseta-azul");
        UUID grupo = UUID.randomUUID();
        when(productSearchService.searchRelevantIds("azul", "es"))
                .thenReturn(Optional.of(List.of(fueraDelGrupo.getId(), delGrupo.getId())));
        when(declarationGroupRepository.findById(grupo)).thenReturn(Optional.of(CustomsDeclarationGroupEntity
                .builder().id(grupo).hs6("620443").material("POLYESTER").usageCode("DRESS").build()));
        when(productRepository.idsForCustomsTerna(ProductStatus.ACTIVE, "620443", "POLYESTER", "DRESS"))
                .thenReturn(List.of(delGrupo.getId()));
        when(productRepository.searchStorefrontByIds(eq(ProductStatus.ACTIVE), eq(List.of(delGrupo.getId())), any(),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(List.of(delGrupo));

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es",
                new ProductListFilters("azul", null, null, null, null, null, null, null, null, null, null, null,
                        null, null, List.of(grupo)),
                null);

        assertThat(pagina.totalElements()).isEqualTo(1);
    }

    private ProductListFilters porGrupo(UUID grupo) {
        return new ProductListFilters(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, List.of(grupo));
    }

    private ProductListFilters filtros(String q) {
        return ProductListFilters.basic(q, null, null, null, null);
    }

    private ProductEntity producto(String slug) {
        ProductEntity p = ProductEntity.builder().slug(slug).titleZh(slug).status(ProductStatus.ACTIVE)
                .basePrice(BigDecimal.ONE).build();
        p.setId(UUID.randomUUID());
        nombres.put(p.getId(), slug);
        return p;
    }

    private ProductSummaryView resumen(ProductEntity p, BigDecimal displayPrice) {
        String slug = nombres.getOrDefault(p.getId(), p.getSlug());
        return new ProductSummaryView(p.getId(), slug, slug, null, null, "CNY", null, 0, null, "ACTIVE", null,
                displayPrice, "EUR", "€", null, null, null, false);
    }
}
