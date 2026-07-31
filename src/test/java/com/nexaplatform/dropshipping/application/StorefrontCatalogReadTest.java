package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.StorefrontViews.CategoryBreadcrumb;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductMapper;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.search.SupplierSearchService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryTranslationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductVariantEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Sort;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lecturas del escaparate: categorías, proveedores, variantes y favoritos.
 *
 * <p>Es lo que ve el comprador. Un fallo aquí no rompe nada por dentro, pero deja la tienda mostrando
 * categorías sin nombre, migas de pan al revés o favoritos de productos ya retirados.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StorefrontCatalogReadTest {

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

    private static CategoryEntity category(String slug, String nameZh, CategoryEntity parent) {
        CategoryEntity c = new CategoryEntity();
        c.setId(UUID.randomUUID());
        c.setSlug(slug);
        c.setNameZh(nameZh);
        c.setParent(parent);
        c.setTranslations(new ArrayList<>());
        return c;
    }

    private static CategoryTranslationEntity translation(String lang, String name) {
        CategoryTranslationEntity t = new CategoryTranslationEntity();
        t.setLanguage(lang);
        t.setName(name);
        return t;
    }

    // ---------------------------------------------------------------- nombre traducido

    @Test
    void laCategoriaSeMuestraEnElIdiomaPedido() {
        CategoryEntity c = category("moda-relojes", "手表", null);
        c.getTranslations().add(translation("es", "Relojes"));
        c.getTranslations().add(translation("en", "Watches"));

        assertThat(CatalogStorefrontReadService.translatedName(c, "es")).isEqualTo("Relojes");
        assertThat(CatalogStorefrontReadService.translatedName(c, "EN")).isEqualTo("Watches");
    }

    @Test
    void sinTraduccionParaEseIdiomaSeMuestraElNombreDeOrigen() {
        // Mejor el chino que un hueco: una categoría sin nombre no se puede ni pinchar.
        CategoryEntity c = category("moda-relojes", "手表", null);
        c.getTranslations().add(translation("es", "Relojes"));

        assertThat(CatalogStorefrontReadService.translatedName(c, "fr")).isEqualTo("手表");
    }

    // ---------------------------------------------------------------- resolución por id o slug

    @Test
    void laCategoriaSeEncuentraTantoPorIdentificadorComoPorNombreEnLaUrl() {
        // La URL del escaparate lleva el slug; el admin usa el identificador. Los dos tienen que funcionar.
        CategoryEntity c = category("moda-relojes", "手表", null);
        when(categoryRepository.findById(c.getId())).thenReturn(Optional.of(c));
        when(categoryRepository.findBySlug("moda-relojes")).thenReturn(Optional.of(c));

        assertThat(service.resolveCategory(c.getId().toString())).isSameAs(c);
        assertThat(service.resolveCategory("moda-relojes")).isSameAs(c);
    }

    @Test
    void unaCategoriaQueNoExisteDaNoEncontradoEnLugarDeUnErrorDeFormato() {
        // Un slug cualquiera no es un UUID: el fallo tiene que ser "no existe", no "formato inválido".
        when(categoryRepository.findBySlug("no-existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCategory("no-existe")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void unIdentificadorConFormaDeUuidPeroInexistenteTambienDaNoEncontrado() {
        UUID id = UUID.randomUUID();
        String idAsText = id.toString();
        when(categoryRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCategory(idAsText)).isInstanceOf(NotFoundException.class);
    }

    // ---------------------------------------------------------------- migas de pan

    @Test
    void lasMigasDePanVanDeLaRaizALaCategoriaActual() {
        // Al revés, el usuario leería "Relojes > Moda > Inicio" y no podría subir de nivel.
        CategoryEntity raiz = category("moda", "时尚", null);
        raiz.getTranslations().add(translation("es", "Moda"));
        CategoryEntity hija = category("moda-relojes", "手表", raiz);
        hija.getTranslations().add(translation("es", "Relojes"));
        when(categoryRepository.findBySlug("moda-relojes")).thenReturn(Optional.of(hija));

        List<CategoryBreadcrumb> migas =
                service.categoryBreadcrumb("moda-relojes", "es");

        assertThat(migas).extracting("name").containsExactly("Moda", "Relojes");
    }

    @Test
    void unaCategoriaRaizTieneUnaSolaMiga() {
        CategoryEntity raiz = category("moda", "时尚", null);
        raiz.getTranslations().add(translation("es", "Moda"));
        when(categoryRepository.findBySlug("moda")).thenReturn(Optional.of(raiz));

        assertThat(service.categoryBreadcrumb("moda", "es")).hasSize(1);
    }

    // ---------------------------------------------------------------- ordenación

    @ParameterizedTest
    @CsvSource({
            "price_asc,  basePrice,      ASC",
            "price_desc, basePrice,      DESC",
            "newest,     createdAt,      DESC",
            "sales,      monthlySales,   DESC",
            "lists,      monthlySales,   DESC",
            "rating,     rating,         DESC",
            "inventory,  inventoryCount, DESC"
    })
    void cadaOrdenPedidoSeTraduceASuCampo(String sort, String field, String direction) {
        Sort spec = service.sortFor(sort);

        assertThat(spec.getOrderFor(field)).isNotNull();
        assertThat(spec.getOrderFor(field).getDirection()).isEqualTo(Sort.Direction.valueOf(direction));
    }

    @ParameterizedTest
    @ValueSource(strings = {"best_match", "cualquier_cosa"})
    void sinOrdenReconocidoSeUsaLaRelevancia(String sort) {
        assertThat(service.sortFor(sort).getOrderFor("trendScore")).isNotNull();
    }

    @Test
    void sinIndicarOrdenTambienSeUsaLaRelevancia() {
        assertThat(service.sortFor(null).getOrderFor("trendScore")).isNotNull();
    }

    // ---------------------------------------------------------------- favoritos

    private ProductEntity product(ProductStatus status) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setStatus(status);
        return p;
    }

    @Test
    void losFavoritosSalenEnElOrdenEnQueElUsuarioLosGuardo() {
        ProductEntity a = product(ProductStatus.ACTIVE);
        ProductEntity b = product(ProductStatus.ACTIVE);
        // El repositorio devuelve lo que le da la gana; el orden lo pone la lista del usuario.
        when(productRepository.findAllById(any())).thenReturn(List.of(b, a));
        when(productMapper.toSummary(any(), anyString())).thenAnswer(i -> summaryOf(i.getArgument(0)));

        var page = service.favorites(List.of(a.getId(), b.getId()), 0, 10, "es");

        assertThat(page.items()).hasSize(2);
        assertThat(page.items().get(0).id()).isEqualTo(a.getId());
    }

    @Test
    void unFavoritoDeUnProductoRetiradoNoSeMuestra() {
        // El producto puede haberse dado de baja después de marcarlo; enseñarlo llevaría a una ficha rota.
        ProductEntity activo = product(ProductStatus.ACTIVE);
        ProductEntity retirado = product(ProductStatus.ARCHIVED);
        when(productRepository.findAllById(any())).thenReturn(List.of(activo, retirado));
        when(productMapper.toSummary(any(), anyString())).thenAnswer(i -> summaryOf(i.getArgument(0)));

        var page = service.favorites(List.of(activo.getId(), retirado.getId()), 0, 10, "es");

        assertThat(page.items()).hasSize(1);
        assertThat(page.totalElements()).isEqualTo(1);
    }

    @Test
    void sinFavoritosLaPaginaSaleVaciaSinConsultarElCatalogo() {
        assertThat(service.favorites(List.of(), 0, 10, "es").items()).isEmpty();
        assertThat(service.favorites(null, 0, 10, "es").items()).isEmpty();
    }

    @Test
    void losFavoritosSePaginanSinSalirseDeLaLista() {
        // Pedir una página más allá del final debe devolver vacío, no reventar con IndexOutOfBounds.
        ProductEntity a = product(ProductStatus.ACTIVE);
        when(productRepository.findAllById(any())).thenReturn(List.of(a));
        when(productMapper.toSummary(any(), anyString())).thenAnswer(i -> summaryOf(i.getArgument(0)));

        assertThat(service.favorites(List.of(a.getId()), 5, 10, "es").items()).isEmpty();
        assertThat(service.favorites(List.of(a.getId()), 5, 10, "es").totalElements()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- variantes

    @Test
    void laVarianteMuestraSuImagenEspejadaAntesQueLaDeOrigen() {
        // La espejada es la que sobrevive: la de origen puede desaparecer sin avisar.
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(UUID.randomUUID());
        v.setImageCdnUrl("https://cdn/roja.jpg");
        v.setImageSourceUrl("https://1688/roja.jpg");

        assertThat(service.variantView(v).imageUrl()).isEqualTo("https://cdn/roja.jpg");
    }

    @Test
    void sinImagenEspejadaLaVarianteMuestraLaDeOrigen() {
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(UUID.randomUUID());
        v.setImageSourceUrl("https://1688/roja.jpg");

        assertThat(service.variantView(v).imageUrl()).isEqualTo("https://1688/roja.jpg");
    }

    @Test
    void unaVarianteSinOpcionesSeMuestraConUnMapaVacioYNoConNulo() {
        // La ficha recorre las opciones para pintar el selector; un nulo la dejaría en blanco.
        ProductVariantEntity v = new ProductVariantEntity();
        v.setId(UUID.randomUUID());

        assertThat(service.variantView(v).options()).isEmpty();
    }

    // ---------------------------------------------------------------- proveedor

    @Test
    void laFichaDelProveedorLlevaSuNumeroRealDeProductos() {
        SupplierEntity s = new SupplierEntity();
        s.setId(UUID.randomUUID());
        s.setName("Shenzhen Watch Co.");
        when(productRepository.countBySupplierId(s.getId())).thenReturn(42L);

        assertThat(service.supplierView(s).productCount()).isEqualTo(42L);
    }

    private static ProductSummaryView summaryOf(ProductEntity p) {
        ProductSummaryView v =
                mock(ProductSummaryView.class);
        when(v.id()).thenReturn(p.getId());
        return v;
    }
}
