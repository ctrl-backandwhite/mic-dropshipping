package com.nexaplatform.dropshipping.api.mapper;

import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.CategoryView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.SupplierView;
import com.nexaplatform.dropshipping.api.controller.StorefrontCatalogController.VariantView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    @InjectMocks
    CatalogStorefrontReadService service;

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
        when(categoryRepository.findAllWithTranslations())
                .thenReturn(List.of(raizB, hijoA2, raizA, hijoA1));
        when(categoryRepository.productCountByCategory())
                .thenReturn(List.<Object[]>of(new Object[] {raizA.getId(), 9L}));

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

    @Test
    void cadaCriterioDeOrdenSeTraduceASuColumna() {
        assertThat(service.sortFor("price_asc")).isEqualTo(Sort.by(Sort.Direction.ASC, "basePrice"));
        assertThat(service.sortFor("price_desc")).isEqualTo(Sort.by(Sort.Direction.DESC, "basePrice"));
        assertThat(service.sortFor("newest")).isEqualTo(Sort.by(Sort.Direction.DESC, "createdAt"));
        assertThat(service.sortFor("sales")).isEqualTo(Sort.by(Sort.Direction.DESC, "monthlySales"));
        assertThat(service.sortFor("lists")).isEqualTo(Sort.by(Sort.Direction.DESC, "monthlySales"));
        assertThat(service.sortFor("rating")).isEqualTo(Sort.by(Sort.Direction.DESC, "rating"));
        assertThat(service.sortFor("inventory")).isEqualTo(Sort.by(Sort.Direction.DESC, "inventoryCount"));
    }

    /** Sin criterio (o con uno desconocido) manda la relevancia: nunca un orden arbitrario. */
    @Test
    void sinCriterioDeOrdenMandaLaRelevancia() {
        assertThat(service.sortFor(null)).isEqualTo(Sort.by(Sort.Direction.DESC, "trendScore"));
        assertThat(service.sortFor("inventado")).isEqualTo(Sort.by(Sort.Direction.DESC, "trendScore"));
    }

    /* ============================ listado de productos ============================ */

    /** El tamaño de página se acota a 100 aunque lo pidan mayor. */
    @Test
    void elTamanoDePaginaDelListadoSeAcotaACien() {
        when(productRepository.searchStorefront(eq(ProductStatus.ACTIVE), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(Pageable.class)))
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
                any(), any(), any(), any(), any(), any(Pageable.class)))
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
                any(), any(), any(), any(), any(), any(Pageable.class)))
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
                any(), any(), any(), any(), any(), any(Pageable.class)))
                        .thenReturn(new PageImpl<>(List.of(conCe, sinCert)));
        when(productMapper.toSummary(conCe, "es")).thenReturn(resumen("con-ce", new BigDecimal("10")));

        ProductListFilters filtros = new ProductListFilters(null, null, null, null, null, null, null, null, null,
                null, null, "ce", null);
        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", filtros, null);

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("con-ce");
    }

    /** El filtro de verificados del admin distingue los revisados de los pendientes (nulo = pendiente). */
    @Test
    void elFiltroDeVerificadosSepararLosRevisadosDeLosPendientes() {
        ProductEntity revisado = producto("revisado");
        revisado.setVerified(true);
        ProductEntity pendiente = producto("pendiente");
        pendiente.setVerified(null);
        when(productRepository.searchStorefront(eq(ProductStatus.ACTIVE), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(Pageable.class)))
                        .thenReturn(new PageImpl<>(List.of(revisado, pendiente)));
        when(productMapper.toSummary(pendiente, "es")).thenReturn(resumen("pendiente", new BigDecimal("10")));

        ProductListFilters soloPendientes = new ProductListFilters(null, null, null, null, null, null, null, null,
                null, null, null, null, Boolean.FALSE);
        PageResponse<ProductSummaryView> pagina = service.productListFull(0, 20, "es", soloPendientes, null);

        assertThat(pagina.items()).extracting(ProductSummaryView::slug).containsExactly("pendiente");
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
        return new ProductSummaryView(UUID.randomUUID(), slug, slug, null, null, "CNY", null, 0, null, "ACTIVE", null,
                displayPrice, "EUR", "€", null, null, null, false);
    }
}
