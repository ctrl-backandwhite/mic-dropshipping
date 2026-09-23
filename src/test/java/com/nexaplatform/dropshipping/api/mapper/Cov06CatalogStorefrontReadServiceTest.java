package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.SupplierView;
import com.nexaplatform.dropshipping.api.dto.StorefrontViews.VariantView;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.ProductViewHistoryService;
import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.cache.CacheConfig;
import com.nexaplatform.dropshipping.infrastructure.integration.currency.CurrencyHolder;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService.IndexedSupplier;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proyección de lectura del escaparate (categorías, proveedores, variantes y listado de productos).
 * Todo lo que aquí se decide lo ve el comprador: qué nombre lleva la categoría en su idioma, qué
 * variantes puede elegir y qué productos entran en el rango de precio de SU divisa.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov06CatalogStorefrontReadServiceTest {

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

    @InjectMocks
    CatalogStorefrontReadService service;

    /**
     * Precio de venta por defecto para cualquier variante: los tests de imagen/opciones/sku no miran
     * el precio, pero variantView SIEMPRE precia (nunca sirve el coste CNY del proveedor).
     */
    @org.junit.jupiter.api.BeforeEach
    void precioDeVentaPorDefecto() {
        // El listado consulta reachFilter en cada llamada; sin promoción activa devuelve vacío (sin filtro).
        org.mockito.Mockito.lenient().when(promotionService.reachFilter(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.empty());
        PricingService.PricedAmount venta = org.mockito.Mockito.mock(PricingService.PricedAmount.class);
        org.mockito.Mockito.lenient().when(venta.displayAmount()).thenReturn(java.math.BigDecimal.ONE);
        org.mockito.Mockito.lenient().when(pricingService.priceFor(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(ProductVariantEntity.class))).thenReturn(venta);
    }

    /* ============================ nombre traducido ============================ */

    /** El idioma se compara sin distinguir mayúsculas: "ES" y "es" son el mismo idioma. */
    @Test
    void elNombreDeLaCategoriaSaleEnElIdiomaPedidoSinDistinguirCaja() {
        CategoryEntity c = categoria("moda", "服装", 0);
        c.setTranslations(new ArrayList<>(List.of(traduccion(c, "es", "Moda"), traduccion(c, "en", "Fashion"))));

        assertThat(CatalogStorefrontReadService.translatedName(c, "ES")).isEqualTo("Moda");
        assertThat(CatalogStorefrontReadService.translatedName(c, "en")).isEqualTo("Fashion");
    }

    /** Sin traducción para ese idioma se enseña el nombre chino: nunca una celda vacía en el menú. */
    @Test
    void sinTraduccionSeCaeAlNombreChino() {
        CategoryEntity c = categoria("moda", "服装", 0);

        assertThat(CatalogStorefrontReadService.translatedName(c, "nl")).isEqualTo("服装");
    }

    /* ============================ resolución de categoría ============================ */

    /** La ruta admite id o slug: si el texto es un UUID se busca por id, si no, por slug. */
    @Test
    void laCategoriaSeResuelvePorIdOPorSlug() {
        UUID id = UUID.randomUUID();
        CategoryEntity porId = categoria("moda", "服装", 0);
        CategoryEntity porSlug = categoria("zapatos", "鞋", 1);
        when(categoryRepository.findById(id)).thenReturn(Optional.of(porId));
        when(categoryRepository.findBySlug("zapatos")).thenReturn(Optional.of(porSlug));

        assertThat(service.resolveCategory(id.toString())).isSameAs(porId);
        assertThat(service.resolveCategory("zapatos")).isSameAs(porSlug);
    }

    @Test
    void unSlugQueNoExisteEs404() {
        when(categoryRepository.findBySlug("no-existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCategory("no-existe")).isInstanceOf(NotFoundException.class);
    }

    /** Un id con forma de UUID que no está en la tabla NO debe reintentarse como slug: es un 404. */
    @Test
    void unIdConFormaDeUuidQueNoExisteEs404SinBuscarPorSlug() {
        UUID id = UUID.randomUUID();
        String idComoTexto = id.toString();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCategory(idComoTexto)).isInstanceOf(NotFoundException.class);
        verify(categoryRepository, never()).findBySlug(anyString());
    }

    /* ============================ migas y árbol ============================ */

    /** Las migas van de la RAÍZ a la categoría pedida: al revés, el usuario navegaría hacia atrás. */
    @Test
    void lasMigasVanDeLaRaizALaCategoriaPedida() {
        CategoryEntity raiz = categoria("moda", "服装", 0);
        CategoryEntity hijo = categoria("mujer", "女装", 0);
        hijo.setParent(raiz);
        CategoryEntity nieto = categoria("vestidos", "连衣裙", 0);
        nieto.setParent(hijo);
        when(categoryRepository.findBySlug("vestidos")).thenReturn(Optional.of(nieto));

        List<CategoryBreadcrumb> migas = service.categoryBreadcrumb("vestidos", "es");

        assertThat(migas).extracting(CategoryBreadcrumb::slug).containsExactly("moda", "mujer", "vestidos");
    }

    /** El árbol se arma en memoria: raíces e hijos ordenados por posición y con su número de productos. */
    @Test
    void elArbolDeCategoriasSeOrdenaPorPosicionYLlevaElConteo() {
        CategoryEntity raizB = categoria("b", "B", 2);
        CategoryEntity raizA = categoria("a", "A", 1);
        CategoryEntity hijoA2 = categoria("a2", "A2", 5);
        hijoA2.setParent(raizA);
        CategoryEntity hijoA1 = categoria("a1", "A1", 1);
        hijoA1.setParent(raizA);
        when(categoryRepository.findAllWithTranslations()).thenReturn(List.of(raizB, hijoA2, raizA, hijoA1));
        when(categoryRepository.productCountByCategory())
                .thenReturn(List.<Object[]>of(new Object[]{raizA.getId(), 9L}));

        List<CategoryView> arbol = service.categoriesTree("es");

        assertThat(arbol).extracting(CategoryView::slug).containsExactly("a", "b");
        assertThat(arbol.get(0).directProductCount()).isEqualTo(9);
        assertThat(arbol.get(0).children()).extracting(CategoryView::slug).containsExactly("a1", "a2");
        assertThat(arbol.get(1).directProductCount()).isZero(); // sin fila en el GROUP BY → 0, no nulo
    }

    /** El listado plano solo devuelve las raíces (el menú de primer nivel), no el árbol entero. */
    @Test
    void elListadoPlanoSoloDevuelveLasRaices() {
        CategoryEntity raiz = categoria("moda", "服装", 0);
        when(categoryRepository.findByParentIsNullOrderByPositionAsc()).thenReturn(List.of(raiz));
        when(categoryRepository.productCountByCategory()).thenReturn(List.of());

        List<CategoryView> plano = service.categoriesFlat("es");

        assertThat(plano).hasSize(1);
        assertThat(plano.get(0).children()).isEmpty();
    }

    /* ============================ proveedores ============================ */

    /** Con el índice disponible los proveedores salen de ahí y no se toca la base de datos. */
    @Test
    void losProveedoresSeSirvenDelIndiceCuandoEstaDisponible() {
        IndexedSupplier fila = new IndexedSupplier(UUID.randomUUID(), "ext", "Fábrica", "工厂", "CN", "Yiwu",
                new BigDecimal("4.5"), 6, true, false, 21);
        when(supplierSearchService.listFromIndex(null)).thenReturn(Optional.of(List.of(fila)));

        List<SupplierView> vistas = service.suppliers();

        assertThat(vistas).hasSize(1);
        assertThat(vistas.get(0).productCount()).isEqualTo(21);
        verify(supplierRepository, never()).findAll();
    }

    /** Índice caído: se listan de la BD ordenados por nombre SIN distinguir mayúsculas. */
    @Test
    void sinIndiceLosProveedoresSeOrdenanPorNombreIgnorandoLaCaja() {
        SupplierEntity zeta = proveedor("zeta");
        SupplierEntity alfa = proveedor("Alfa");
        when(supplierSearchService.listFromIndex(null)).thenReturn(Optional.empty());
        when(supplierRepository.findAll()).thenReturn(List.of(zeta, alfa));

        List<SupplierView> vistas = service.suppliers();

        assertThat(vistas).extracting(SupplierView::name).containsExactly("Alfa", "zeta");
    }

    @Test
    void elDetalleDeUnProveedorInexistenteEs404() {
        UUID id = UUID.randomUUID();
        when(supplierRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.supplierDetail(id)).isInstanceOf(NotFoundException.class);
    }

    /* ============================ variantes ============================ */

    /** Una variante desactivada no se puede comprar: no aparece entre las opciones del producto. */
    @Test
    void lasVariantesDesactivadasNoSeOfrecen() {
        UUID productId = UUID.randomUUID();
        when(variantRepository.findByProductId(productId))
                .thenReturn(List.of(variante("SKU-A", true), variante("SKU-B", false)));

        List<VariantView> vistas = service.variantsForProduct(productId);

        assertThat(vistas).extracting(VariantView::sku).containsExactly("SKU-A");
    }

    /** La foto espejada en nuestro almacenamiento manda sobre la del proveedor (la de origen caduca). */
    @Test
    void laImagenDeLaVarianteSaleDeNuestroAlmacenamientoSiEstaEspejada() {
        ProductVariantEntity espejada = variante("SKU-A", true);
        espejada.setImageCdnUrl("https://cdn/propia.jpg");
        espejada.setImageSourceUrl("https://1688/origen.jpg");
        ProductVariantEntity soloOrigen = variante("SKU-B", true);
        soloOrigen.setImageSourceUrl("https://1688/origen.jpg");

        assertThat(service.variantView(espejada).imageUrl()).isEqualTo("https://cdn/propia.jpg");
        assertThat(service.variantView(soloOrigen).imageUrl()).isEqualTo("https://1688/origen.jpg");
    }

    /** Sin opciones no se devuelve null: el front recorre el mapa sin comprobarlo. */
    @Test
    void unaVarianteSinOpcionesDevuelveUnMapaVacio() {
        ProductVariantEntity v = variante("SKU-A", true);
        v.setOptions(null);

        assertThat(service.variantView(v).options()).isEmpty();
    }

    /** La búsqueda por SKU acepta también el identificador externo, y sin distinguir mayúsculas. */
    @Test
    void laVarianteSeEncuentraPorSkuOPorIdentificadorExterno() {
        UUID productId = UUID.randomUUID();
        ProductVariantEntity v = variante("SKU-A", true);
        v.setExternalId("EXT-77");
        when(variantRepository.findByProductId(productId)).thenReturn(List.of(v));

        assertThat(service.variantBySku(productId, "sku-a").sku()).isEqualTo("SKU-A");
        assertThat(service.variantBySku(productId, "ext-77").sku()).isEqualTo("SKU-A");
    }

    @Test
    void unSkuQueNoExisteEs404() {
        UUID productId = UUID.randomUUID();
        when(variantRepository.findByProductId(productId)).thenReturn(List.of(variante("SKU-A", true)));

        assertThatThrownBy(() -> service.variantBySku(productId, "SKU-Z")).isInstanceOf(NotFoundException.class);
    }

    /* ============================ orden del listado ============================ */

    /**
     * Cada criterio ordena por su columna Y TERMINA EN {@code id}.
     *
     * <p>El desempate no es cosmético: los campos por los que se ordena empatan en masa —5.181 de 5.485
     * productos comparten {@code trendScore = 0}—, y sin un criterio final estable PostgreSQL devuelve las
     * filas empatadas en el orden que le convenga, que cambia entre consultas. El efecto medido era que la
     * misma página del catálogo devolvía productos distintos, así que al comprador le salían repetidos unos
     * y otros no le salían nunca. Si alguien quita el desempate, estos casos fallan.
     */
    @Test
    void cadaCriterioDeOrdenSeTraduceASuColumnaYDesempataPorId() {
        Sort id = Sort.by(Sort.Direction.ASC, "id");
        assertThat(service.sortFor("price_asc")).isEqualTo(Sort.by(Sort.Direction.ASC, "basePrice").and(id));
        assertThat(service.sortFor("price_desc")).isEqualTo(Sort.by(Sort.Direction.DESC, "basePrice").and(id));
        assertThat(service.sortFor("newest")).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt").and(id));
        assertThat(service.sortFor("sales")).isEqualTo(Sort.by(Sort.Direction.DESC, "monthlySales").and(id));
        assertThat(service.sortFor("lists")).isEqualTo(Sort.by(Sort.Direction.DESC, "monthlySales").and(id));
        assertThat(service.sortFor("rating")).isEqualTo(Sort.by(Sort.Direction.DESC, "rating").and(id));
        assertThat(service.sortFor("inventory")).isEqualTo(Sort.by(Sort.Direction.DESC, "inventoryCount").and(id));
    }

    /**
     * Con semilla, el criterio NO cambia: lo que se baraja es el desempate.
     *
     * <p>Es la diferencia entre «enseñar variedad» y «romper la ordenación». Si el azar entrara en el
     * criterio, pedir «precio ascendente» dejaría de ordenar por precio.
     */
    @Test
    void laSemillaBarajaElDesempatePeroRespetaElCriterio() {
        Sort conSemilla = service.sortFor("price_asc", 7);

        assertThat(conSemilla).hasSize(2);
        assertThat(conSemilla.iterator().next()).isEqualTo(Sort.Order.asc("basePrice"));
        // El segundo ya no es `id`: es la expresión barajada.
        assertThat(conSemilla.getOrderFor("id")).isNull();
    }

    /** Sin semilla se mantiene EXACTAMENTE el orden fijo de siempre: el cambio no toca a quien no la manda. */
    @Test
    void sinSemillaElOrdenSigueSiendoElDeSiempre() {
        assertThat(service.sortFor("price_asc", null)).isEqualTo(service.sortFor("price_asc"));
    }

    /**
     * La misma semilla da SIEMPRE el mismo orden, y semillas distintas dan órdenes distintos.
     *
     * <p>Lo primero es lo que sostiene la paginación: con el scroll infinito, si la página 3 se pidiera con
     * otro orden que la 2, al comprador le saldrían productos repetidos y otros no le saldrían nunca.
     */
    @Test
    void laMismaSemillaDaSiempreElMismoOrden() {
        assertThat(service.sortFor("best_match", 7)).isEqualTo(service.sortFor("best_match", 7));
        assertThat(service.sortFor("best_match", 7)).isNotEqualTo(service.sortFor("best_match", 8));
    }

    /**
     * Cualquier entero cae dentro de las 32 barajas, incluidos los negativos y los enormes.
     *
     * <p>La semilla la manda el navegador, así que llega texto de fuera hasta la consulta. No se sanea con
     * una expresión regular: se recibe como {@code int} y se reduce con {@code floorMod}, de modo que lo
     * que se interpola solo puede ser un número entre 0 y 31. Aquí se comprueba que no hay valor —ni
     * negativo, ni desbordado— que se escape de ese rango.
     */
    @Test
    void cualquierEnteroCaeDentroDeLasBarajas() {
        assertThat(service.sortFor("best_match", -5)).isEqualTo(service.sortFor("best_match", 27));
        assertThat(service.sortFor("best_match", 32)).isEqualTo(service.sortFor("best_match", 0));
        assertThat(service.sortFor("best_match", Integer.MIN_VALUE))
                .isEqualTo(service.sortFor("best_match", Math.floorMod(Integer.MIN_VALUE, 32)));
        // Y en ningún caso la expresión lleva algo que no sean dígitos entre las comillas.
        assertThat(service.sortFor("best_match", Integer.MAX_VALUE).toString()).matches(".*'\\d+'.*");
    }

    /**
     * «Variado» deja que mande la baraja: no hay criterio por delante.
     *
     * <p>Es lo que hace que el catálogo enseñe cosas distintas al recargar. Con cualquier otro orden el
     * azar solo rompe EMPATES, y el orden por defecto del escaparate era «más recientes», donde cada
     * producto tiene su fecha y no empata con nadie: la baraja no cambiaba nada. Se detectó mirando el
     * catálogo en el navegador, no en las pruebas, porque estas lo ejercitaban por relevancia —donde
     * 5.181 de 5.485 productos empatan— y allí sí barajaba.
     */
    @Test
    void elOrdenVariadoLoDecideLaBaraja() {
        Sort variado = service.sortFor("random", 7);

        assertThat(variado).hasSize(1);
        assertThat(variado.getOrderFor("createdAt")).isNull();
        assertThat(variado.getOrderFor("trendScore")).isNull();
        assertThat(variado).isNotEqualTo(service.sortFor("random", 8));
    }

    /** Sin semilla, «variado» cae al orden estable por id: nunca a un orden que PostgreSQL decida. */
    @Test
    void elOrdenVariadoSinSemillaSigueSiendoEstable() {
        assertThat(service.sortFor("random", null)).isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    /** Sin criterio (o con uno desconocido) manda la relevancia: nunca un orden arbitrario. */
    @Test
    void sinCriterioDeOrdenMandaLaRelevancia() {
        Sort esperado = Sort.by(Sort.Direction.DESC, "trendScore").and(Sort.by(Sort.Direction.ASC, "id"));
        assertThat(service.sortFor(null)).isEqualTo(esperado);
        assertThat(service.sortFor("inventado")).isEqualTo(esperado);
    }

    /* ============================ listado de productos ============================ */

    /** El tamaño de página se acota a 100 aunque lo pidan mayor. */
    @Test
    void elTamanoDePaginaDelListadoSeAcotaACien() {
        when(productRepository.searchStorefront(eq(ProductStatus.ACTIVE), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 5000, "es", ProductListFilters.none(),
                null);

        assertThat(pagina.size()).isEqualTo(100);
    }

    /**
     * El filtro de precio se aplica sobre el precio YA convertido a la divisa del usuario: filtrarlo en
     * SQL (que solo conoce el precio en yuanes) daría rangos sin sentido para quien navega en euros.
     * Un producto sin precio queda fuera cuando hay filtro.
     */
    @Test
    void elFiltroDePrecioTrabajaSobreElPrecioDeLaDivisaDelUsuario() {
        ProductEntity barato = producto("barato");
        ProductEntity caro = producto("caro");
        ProductEntity sinPrecio = producto("sin-precio");
        when(productRepository.searchStorefront(eq(ProductStatus.ACTIVE), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(barato, caro, sinPrecio)));
        when(productMapper.toSummary(barato, "es")).thenReturn(resumen("barato", new BigDecimal("10.00")));
        when(productMapper.toSummary(caro, "es")).thenReturn(resumen("caro", new BigDecimal("90.00")));
        when(productMapper.toSummary(sinPrecio, "es")).thenReturn(resumen("sin-precio", null));

        ProductListFilters filtros = ProductListFilters.basic(null, null, null, new BigDecimal("5.00"),
                new BigDecimal("50.00"));
        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros, null);

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("barato");
        assertThat(pagina.totalElements()).isEqualTo(1);
    }

    /** Los extremos del rango entran: el usuario que filtra "hasta 90" espera ver el de 90. */
    @Test
    void elRangoDePrecioIncluyeSusExtremos() {
        ProductEntity justo = producto("justo");
        when(productRepository.searchStorefront(eq(ProductStatus.ACTIVE), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(justo)));
        when(productMapper.toSummary(justo, "es")).thenReturn(resumen("justo", new BigDecimal("90.00")));

        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es",
                ProductListFilters.basic(null, null, null, null, new BigDecimal("90.00")), null);

        assertThat(pagina.items()).hasSize(1);
    }

    /** Filtro de certificación: coincidencia parcial y sin distinguir mayúsculas ("ce" encuentra "CE-EMC"). */
    @Test
    void elFiltroDeCertificacionEsParcialYSinDistinguirCaja() {
        ProductEntity conCe = producto("con-ce");
        conCe.setCertifications(List.of("CE-EMC"));
        ProductEntity sinCert = producto("sin-cert");
        when(productRepository.searchStorefront(eq(ProductStatus.ACTIVE), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(conCe, sinCert)));
        when(productMapper.toSummary(conCe, "es")).thenReturn(resumen("con-ce", new BigDecimal("10")));

        ProductListFilters filtros = new ProductListFilters(null, null, null, null, null, null, null, null, null, null,
                null, "ce", null, null, null);
        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros, null);

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("con-ce");
    }

    /**
     * El filtro de verificados viaja a la CONSULTA, no se aplica en memoria.
     *
     * <p>Antes se filtraba sobre el resultado, y para eso el servicio tomaba un atajo: cargaba las primeras
     * 5.000 filas y filtraba sobre ellas. Con un catálogo mayor —hoy son 5.491 productos— los de fuera del
     * corte no se filtraban NUNCA, así que «Verificado: No» devolvía cero sin que nada fallara. Y como el
     * orden no era estable, qué 5.000 entraban cambiaba en cada consulta: el filtro funcionaba en un
     * entorno y no en otro. Lo que se fija aquí es que el valor llega al repositorio.
     */
    @Test
    void elFiltroDeVerificadosViajaALaConsulta() {
        ProductEntity pendiente = producto("pendiente");
        pendiente.setVerified(null);
        when(productRepository.searchStorefront(eq(ProductStatus.ACTIVE), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), anyString(), anyBoolean(), anyBoolean(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(pendiente)));
        when(productMapper.toSummary(pendiente, "es")).thenReturn(resumen("pendiente", new BigDecimal("10")));

        ProductListFilters soloPendientes = new ProductListFilters(null, null, null, null, null, null, null, null, null,
                null, null, null, Boolean.FALSE, null, null);
        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", soloPendientes, null);

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("pendiente");
        // El 13.º argumento es `verified`: tiene que llegar FALSE, no null.
        ArgumentCaptor<Boolean> captor = ArgumentCaptor.forClass(Boolean.class);
        verify(productRepository).searchStorefront(eq(ProductStatus.ACTIVE), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), captor.capture(), anyString(), anyBoolean(), anyBoolean(),
                any(Pageable.class));
        assertThat(captor.getValue()).isFalse();
    }

    /* ============================ favoritos ============================ */

    @Test
    void sinFavoritosLaPaginaVieneVaciaYNoSeConsultaElCatalogo() {
        PageResponse<ProductSummaryView> pagina = service.favorites(List.of(), 0, 20, "es");

        assertThat(pagina.items()).isEmpty();
        assertThat(pagina.totalElements()).isZero();
        verify(productRepository, never()).findAllById(any());
    }

    /**
     * Los favoritos se devuelven en el ORDEN en que llegan (del más reciente al más antiguo) y los
     * productos que ya no están activos se omiten en vez de enseñarse como comprables.
     */
    @Test
    void losFavoritosConservanSuOrdenYOmitenLosNoActivos() {
        ProductEntity reciente = producto("reciente");
        ProductEntity antiguo = producto("antiguo");
        ProductEntity retirado = producto("retirado");
        retirado.setStatus(ProductStatus.DRAFT);
        List<UUID> ids = List.of(reciente.getId(), retirado.getId(), antiguo.getId());
        when(productRepository.findAllById(ids)).thenReturn(List.of(antiguo, retirado, reciente));
        when(productMapper.toSummary(reciente, "es")).thenReturn(resumen("reciente", new BigDecimal("1")));
        when(productMapper.toSummary(antiguo, "es")).thenReturn(resumen("antiguo", new BigDecimal("1")));

        PageResponse<ProductSummaryView> pagina = service.favorites(ids, 0, 20, "es");

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("reciente", "antiguo");
        assertThat(pagina.totalElements()).isEqualTo(2);
    }

    /** Pedir una página más allá del final devuelve vacío, no una excepción de índice. */
    @Test
    void unaPaginaDeFavoritosFueraDeRangoDevuelveVacio() {
        ProductEntity uno = producto("uno");
        List<UUID> ids = List.of(uno.getId());
        when(productRepository.findAllById(ids)).thenReturn(List.of(uno));
        when(productMapper.toSummary(uno, "es")).thenReturn(resumen("uno", new BigDecimal("1")));

        PageResponse<ProductSummaryView> pagina = service.favorites(ids, 5, 20, "es");

        assertThat(pagina.items()).isEmpty();
        assertThat(pagina.totalElements()).isEqualTo(1);
    }

    /* ============================ vista de categoría con hijos ============================ */

    /** El detalle de una categoría cuenta sus productos con un COUNT indexado, no cargando la tabla. */
    @Test
    void elDetalleDeCategoriaLlevaSuConteoYSusHijos() {
        CategoryEntity raiz = categoria("moda", "服装", 0);
        CategoryEntity hijo = categoria("mujer", "女装", 0);
        hijo.setParent(raiz);
        when(categoryRepository.findBySlug("moda")).thenReturn(Optional.of(raiz));
        when(categoryRepository.findByParent_IdOrderByPositionAsc(raiz.getId())).thenReturn(List.of(hijo));
        when(categoryRepository.findByParent_IdOrderByPositionAsc(hijo.getId())).thenReturn(List.of());
        when(productRepository.countByCategoryId(raiz.getId())).thenReturn(15L);
        when(productRepository.countByCategoryId(hijo.getId())).thenReturn(4L);

        CategoryView vista = service.categoryDetail("moda", "es");

        assertThat(vista.directProductCount()).isEqualTo(15);
        assertThat(vista.children()).hasSize(1);
        assertThat(vista.children().get(0).directProductCount()).isEqualTo(4);
        assertThat(vista.parentId()).isNull();
    }

    /** Los hijos de una categoría se devuelven SIN sus propios descendientes (el menú los carga al abrir). */
    @Test
    void losHijosDeUnaCategoriaVienenSinDescendientes() {
        CategoryEntity raiz = categoria("moda", "服装", 0);
        CategoryEntity hijo = categoria("mujer", "女装", 0);
        hijo.setParent(raiz);
        when(categoryRepository.findBySlug("moda")).thenReturn(Optional.of(raiz));
        when(categoryRepository.findByParent_IdOrderByPositionAsc(raiz.getId())).thenReturn(List.of(hijo));

        List<CategoryView> hijos = service.categoryChildren("moda", "es");

        assertThat(hijos).hasSize(1);
        assertThat(hijos.get(0).children()).isEmpty();
        assertThat(hijos.get(0).parentId()).isEqualTo(raiz.getId());
    }

    /* ============================ helpers ============================ */

    private static CategoryEntity categoria(String slug, String nameZh, int position) {
        CategoryEntity c = CategoryEntity.builder().slug(slug).nameZh(nameZh).position(position).active(true)
                .translations(new ArrayList<>()).build();
        c.setId(UUID.randomUUID());
        return c;
    }

    private static CategoryTranslationEntity traduccion(CategoryEntity c, String lang, String name) {
        return CategoryTranslationEntity.builder().category(c).language(lang).name(name).build();
    }

    private static SupplierEntity proveedor(String name) {
        SupplierEntity s = SupplierEntity.builder().name(name).externalId("ext-" + name).source("1688").build();
        s.setId(UUID.randomUUID());
        return s;
    }

    private static ProductVariantEntity variante(String sku, boolean activa) {
        ProductVariantEntity v = ProductVariantEntity.builder().sku(sku).active(activa).stock(5)
                .price(new BigDecimal("10")).options(Map.of("color", "rojo")).build();
        v.setId(UUID.randomUUID());
        return v;
    }

    private static ProductEntity producto(String slug) {
        ProductEntity p = ProductEntity.builder().slug(slug).titleZh(slug).status(ProductStatus.ACTIVE).build();
        p.setId(UUID.randomUUID());
        return p;
    }

    private static ProductSummaryView resumen(String slug, BigDecimal displayPrice) {
        return new ProductSummaryView(UUID.randomUUID(), slug, slug, null, null, "CNY", null, 0, 0, null, "ACTIVE",
                null, displayPrice, "EUR", "€", null, null, null, false);
    }

    /**
     * La variante se sirve a PRECIO DE VENTA. v.getPrice() es el coste CNY del proveedor, y servirlo
     * por el API público regalaba el margen a cualquier usuario logueado (y a los partners).
     */
    @Test
    void laVarianteNuncaEnsenaElCosteDelProveedor() {
        ProductVariantEntity v = variante("SKU-A", true);
        v.setPrice(new java.math.BigDecimal("5.38")); // coste CNY 1688
        PricingService.PricedAmount venta = org.mockito.Mockito.mock(PricingService.PricedAmount.class,
                org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(venta.displayAmount()).thenReturn(new java.math.BigDecimal("2.70"));
        when(pricingService.priceFor(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(v)))
                .thenReturn(venta);

        assertThat(service.variantView(v).price()).isEqualByComparingTo("2.70");
    }

    /* ============================ historial de visitas ============================ */

    /**
     * El precio guardado con la visita solo vale si está en la MONEDA ACTIVA.
     *
     * <p>Guardar el importe que vio la persona ahorra cincuenta conversiones por página, pero quien
     * cambia de divisa —o quien abrió fichas antes de cambiarla— se encontraba el historial con unos
     * precios en dólares y otros en euros, uno al lado del otro en la misma rejilla.
     */
    @Test
    @DisplayName("el historial no reutiliza el precio guardado en otra moneda")
    void elHistorialNoReutilizaElPrecioGuardadoEnOtraMoneda() {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setStatus(ProductStatus.ACTIVE);
        org.mockito.Mockito.when(productRepository.findAllById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(p));
        ProductViewHistoryService.FichaVista enEuros = new ProductViewHistoryService.FichaVista(p.getId(),
                new java.math.BigDecimal("3.08"), "EUR", "3,08 €");

        CurrencyHolder.set("USD");
        try {
            service.historial(List.of(enEuros), 0, 24, "es");
        } finally {
            CurrencyHolder.clear();
        }

        // Precio a null = se vuelve a calcular en la moneda de hoy, en vez de pintar los euros de ayer.
        org.mockito.Mockito.verify(productMapper).toSummary(org.mockito.ArgumentMatchers.eq(p),
                org.mockito.ArgumentMatchers.eq("es"), org.mockito.ArgumentMatchers.isNull());
        org.mockito.Mockito.verify(pricingService, org.mockito.Mockito.never()).precioYaVisto(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("en la misma moneda sí se reutiliza, que es lo que hace rápida la página")
    void enLaMismaMonedaSiSeReutiliza() {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setStatus(ProductStatus.ACTIVE);
        org.mockito.Mockito.when(productRepository.findAllById(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(p));
        ProductViewHistoryService.FichaVista enDolares = new ProductViewHistoryService.FichaVista(p.getId(),
                new java.math.BigDecimal("7.78"), "usd", "$7.78");

        CurrencyHolder.set("USD");
        try {
            service.historial(List.of(enDolares), 0, 24, "es");
        } finally {
            CurrencyHolder.clear();
        }

        org.mockito.Mockito.verify(pricingService).precioYaVisto(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("usd"), org.mockito.ArgumentMatchers.eq("$7.78"));
    }

    /**
     * Todos los listados de producto comparten el mismo bucket de caché y el mismo precio detrás, así que
     * todos tienen que componer su clave con el MISMO generador. Componerla a mano en SpEL es lo que dejó
     * fuera el canal y el rol en dos de ellos: `productsByCategory` y `productsBySupplier` los sirven a la
     * vez el escaparate (margen 150%) y /api/v1/partner/catalog (margen 75%), de modo que compartían
     * entrada y un comprador podía llevarse el precio de integración —y al revés—. Sin el rol, además, un
     * admin dejaba cacheada la página CON el coste de proveedor para el siguiente anónimo.
     *
     * <p>Se comprueba la anotación y no el resultado a propósito: la clave la compone Spring por proxy, y
     * lo que hay que impedir es justamente que alguien vuelva a escribirla a mano.
     */
    @Test
    @DisplayName("ningún listado de producto compone su clave de caché a mano")
    void ningunListadoDeProductoComponeSuClaveDeCacheAMano() {
        for (Method metodo : CatalogStorefrontReadService.class.getDeclaredMethods()) {
            Cacheable anotacion = metodo.getAnnotation(Cacheable.class);
            if (anotacion == null || !List.of(anotacion.value()).contains(CacheConfig.CACHE_PRODUCT_LIST)) {
                continue;
            }
            assertThat(anotacion.key())
                    .as("%s escribe su clave en SpEL; debe delegar en el generador canónico", metodo.getName())
                    .isEmpty();
            assertThat(anotacion.keyGenerator()).as("%s no usa el generador canónico de clave", metodo.getName())
                    .isEqualTo("currencyAwareKeyGenerator");
        }
    }
}
