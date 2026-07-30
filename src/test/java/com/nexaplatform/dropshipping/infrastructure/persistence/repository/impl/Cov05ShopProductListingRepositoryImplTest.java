package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.model.ShopProductListing;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopConnectionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ShopProductListingEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ShopProductListingEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopConnectionJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ShopProductListingJpaRepositoryAdapter;
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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adaptador de persistencia de los productos publicados en una tienda conectada. Su responsabilidad
 * propia es resolver las relaciones gestionadas (tienda y producto) a partir de los ids planos del
 * modelo de dominio, y hacerlo SOLO en el alta: en una actualización la entidad ya viene enlazada.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov05ShopProductListingRepositoryImplTest {

    @Mock
    ShopProductListingEntityMapper shopProductListingEntityMapper;
    @Mock
    ShopProductListingJpaRepositoryAdapter shopProductListingJpaRepositoryAdapter;
    @Mock
    ShopConnectionJpaRepositoryAdapter shopConnectionJpaRepositoryAdapter;
    @Mock
    ProductRepository productRepository;

    @InjectMocks
    ShopProductListingRepositoryImpl repository;

    private static final UUID LISTING_ID = UUID.fromString("11111111-0000-0000-0000-000000000001");
    private static final UUID SHOP_ID = UUID.fromString("22222222-0000-0000-0000-000000000002");
    private static final UUID PRODUCT_ID = UUID.fromString("33333333-0000-0000-0000-000000000003");

    @BeforeEach
    void setUp() {
        when(shopProductListingJpaRepositoryAdapter.save(any())).thenAnswer(i -> i.getArgument(0));
        when(shopProductListingEntityMapper.toDomain(any())).thenReturn(ShopProductListing.builder().build());
    }

    private ShopProductListing model(UUID id) {
        return ShopProductListing.builder().id(id).shopConnectionId(SHOP_ID).productId(PRODUCT_ID)
                .remoteProductId("gid://shopify/Product/1").status("PUBLISHED").build();
    }

    private ShopConnectionEntity shop() {
        ShopConnectionEntity shop = new ShopConnectionEntity();
        shop.setId(SHOP_ID);
        return shop;
    }

    private ProductEntity product() {
        ProductEntity product = new ProductEntity();
        product.setId(PRODUCT_ID);
        return product;
    }

    private ShopProductListingEntity captureSaved() {
        ArgumentCaptor<ShopProductListingEntity> captor = ArgumentCaptor.forClass(ShopProductListingEntity.class);
        verify(shopProductListingJpaRepositoryAdapter).save(captor.capture());
        return captor.getValue();
    }

    /* ===================== alta ===================== */

    @Test
    @DisplayName("el alta resuelve la tienda y el producto desde sus ids planos")
    void elAltaResuelveTiendaYProducto() {
        when(shopConnectionJpaRepositoryAdapter.findById(SHOP_ID)).thenReturn(Optional.of(shop()));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.of(product()));

        repository.save(model(null));

        ShopProductListingEntity saved = captureSaved();
        assertThat(saved.getShopConnection().getId()).isEqualTo(SHOP_ID);
        assertThat(saved.getProduct().getId()).isEqualTo(PRODUCT_ID);
    }

    @Test
    @DisplayName("no se puede publicar en una tienda que no existe")
    void noSePuedePublicarEnUnaTiendaInexistente() {
        when(shopConnectionJpaRepositoryAdapter.findById(SHOP_ID)).thenReturn(Optional.empty());
        ShopProductListing model = model(null);

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Shop");
        verify(shopProductListingJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    @DisplayName("no se puede publicar un producto que no existe")
    void noSePuedePublicarUnProductoInexistente() {
        when(shopConnectionJpaRepositoryAdapter.findById(SHOP_ID)).thenReturn(Optional.of(shop()));
        when(productRepository.findById(PRODUCT_ID)).thenReturn(Optional.empty());
        ShopProductListing model = model(null);

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Product");
        verify(shopProductListingJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    @DisplayName("sin ids de tienda o producto la relación queda vacía en vez de consultarse")
    void sinIdsLaRelacionQuedaVacia() {
        repository.save(ShopProductListing.builder().status("DRAFT").build());

        ShopProductListingEntity saved = captureSaved();
        assertThat(saved.getShopConnection()).isNull();
        assertThat(saved.getProduct()).isNull();
        verify(shopConnectionJpaRepositoryAdapter, never()).findById(any());
        verify(productRepository, never()).findById(any());
    }

    /* ===================== actualización ===================== */

    @Test
    @DisplayName("actualizar sobre una entidad ya enlazada no vuelve a consultar tienda ni producto")
    void actualizarNoVuelveAResolverLasRelaciones() {
        ShopProductListingEntity managed = new ShopProductListingEntity();
        managed.setId(LISTING_ID);
        managed.setShopConnection(shop());
        managed.setProduct(product());
        when(shopProductListingJpaRepositoryAdapter.findById(LISTING_ID)).thenReturn(Optional.of(managed));

        repository.update(model(LISTING_ID));

        // Dos SELECT por cada publicación sería trabajo inútil en cada sincronización de catálogo.
        verify(shopConnectionJpaRepositoryAdapter, never()).findById(any());
        verify(productRepository, never()).findById(any());
        verify(shopProductListingEntityMapper).updateEntity(managed, model(LISTING_ID));
    }

    @Test
    @DisplayName("actualizar una publicación que ya no existe falla en vez de crear otra")
    void actualizarUnaPublicacionInexistenteFalla() {
        when(shopProductListingJpaRepositoryAdapter.findById(LISTING_ID)).thenReturn(Optional.empty());
        ShopProductListing model = model(LISTING_ID);

        assertThatThrownBy(() -> repository.update(model)).isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Listing");
        verify(shopProductListingJpaRepositoryAdapter, never()).save(any());
    }

    /* ===================== consultas ===================== */

    @Test
    @DisplayName("el listado de una tienda se traduce al modelo de dominio")
    void elListadoDeUnaTiendaSeTraduceAlDominio() {
        List<ShopProductListingEntity> entities = List.of(new ShopProductListingEntity());
        when(shopProductListingJpaRepositoryAdapter.findByShopConnection_Id(SHOP_ID)).thenReturn(entities);
        when(shopProductListingEntityMapper.toDomainList(entities))
                .thenReturn(List.of(ShopProductListing.builder().build()));

        assertThat(repository.findByShopConnectionId(SHOP_ID)).hasSize(1);
    }

    @Test
    @DisplayName("el contador de publicaciones cuenta las de esa tienda")
    void elContadorCuentaLasPublicacionesDeLaTienda() {
        when(shopProductListingJpaRepositoryAdapter.findByShopConnection_Id(SHOP_ID))
                .thenReturn(List.of(new ShopProductListingEntity(), new ShopProductListingEntity()));

        assertThat(repository.countByShopConnectionId(SHOP_ID)).isEqualTo(2);
    }

    @Test
    @DisplayName("buscar por tienda y producto devuelve vacío si esa pareja no está publicada")
    void buscarPorTiendaYProductoDevuelveVacioSiNoExiste() {
        when(shopProductListingJpaRepositoryAdapter.findByShopConnection_IdAndProduct_Id(SHOP_ID, PRODUCT_ID))
                .thenReturn(Optional.empty());

        assertThat(repository.findByShopConnectionIdAndProductId(SHOP_ID, PRODUCT_ID)).isEmpty();
    }

    @Test
    @DisplayName("buscar por tienda y producto traduce la entidad encontrada")
    void buscarPorTiendaYProductoTraduceLaEntidad() {
        when(shopProductListingJpaRepositoryAdapter.findByShopConnection_IdAndProduct_Id(SHOP_ID, PRODUCT_ID))
                .thenReturn(Optional.of(new ShopProductListingEntity()));

        assertThat(repository.findByShopConnectionIdAndProductId(SHOP_ID, PRODUCT_ID)).isPresent();
    }

    @Test
    @DisplayName("pedir una publicación inexistente devuelve nulo, no una excepción")
    void pedirUnaPublicacionInexistenteDevuelveNulo() {
        when(shopProductListingJpaRepositoryAdapter.findById(LISTING_ID)).thenReturn(Optional.empty());

        assertThat(repository.getById(LISTING_ID)).isNull();
    }

    @Test
    @DisplayName("borrar y comprobar existencia delegan en el adaptador de JPA")
    void borrarYComprobarExistenciaDelegan() {
        when(shopProductListingJpaRepositoryAdapter.existsById(LISTING_ID)).thenReturn(true);

        repository.delete(LISTING_ID);

        verify(shopProductListingJpaRepositoryAdapter).deleteById(LISTING_ID);
        assertThat(repository.existsById(LISTING_ID)).isTrue();
    }
}
