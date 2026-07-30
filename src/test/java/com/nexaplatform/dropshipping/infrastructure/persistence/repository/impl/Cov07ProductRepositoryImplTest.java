package com.nexaplatform.dropshipping.infrastructure.persistence.repository.impl;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.domain.enums.MirrorStatus;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.domain.model.Product;
import com.nexaplatform.dropshipping.domain.model.ProductImage;
import com.nexaplatform.dropshipping.domain.model.ProductPriceTier;
import com.nexaplatform.dropshipping.domain.model.ProductTranslation;
import com.nexaplatform.dropshipping.domain.model.ProductVariant;
import com.nexaplatform.dropshipping.domain.model.VariantOption;
import com.nexaplatform.dropshipping.domain.model.VariantValue;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductImageEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductPriceTierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SupplierEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.mapper.ProductEntityMapper;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductJpaRepositoryAdapter;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SupplierJpaRepositoryAdapter;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Reglas del adaptador de persistencia de productos: qué se resuelve contra la base de datos al
 * guardar (proveedor/categoría), cómo se reconstruyen las colecciones anidadas y cómo se sincroniza
 * la escalera de precios, que vive en su propia tabla.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Cov07ProductRepositoryImplTest {

    @Mock
    ProductEntityMapper productEntityMapper;
    @Mock
    ProductJpaRepositoryAdapter productJpaRepositoryAdapter;
    @Mock
    SupplierJpaRepositoryAdapter supplierJpaRepositoryAdapter;
    @Mock
    CategoryJpaRepositoryAdapter categoryJpaRepositoryAdapter;
    @Mock
    ProductPriceTierRepository priceTierRepository;

    @InjectMocks
    ProductRepositoryImpl repository;

    private Product model;

    @BeforeEach
    void setUp() {
        model = Product.builder().slug("camisa-lino").build();
        // El mapper es un doble: sin esto toDomain devolvería null y el adjuntado de tramos reventaría.
        when(productEntityMapper.toDomain(any())).thenAnswer(inv -> Product.builder().slug("camisa-lino").build());
        when(productJpaRepositoryAdapter.save(any())).thenAnswer(inv -> {
            ProductEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return e;
        });
    }

    /* ==================== save: resolución de relaciones ==================== */

    @Test
    void guardarUnProductoConIdInexistenteNoCreaUnoNuevo() {
        UUID id = UUID.randomUUID();
        model.setId(id);
        when(productJpaRepositoryAdapter.findById(id)).thenReturn(Optional.empty());

        // Si insertara en vez de fallar, un id equivocado duplicaría el producto en el catálogo.
        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class);
        verify(productJpaRepositoryAdapter, never()).save(any());
    }

    @Test
    void unProductoSinProveedorNiCategoriaSeGuardaSinConsultarEsasTablas() {
        repository.save(model);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productJpaRepositoryAdapter).save(captor.capture());
        assertThat(captor.getValue().getSupplier()).isNull();
        assertThat(captor.getValue().getCategory()).isNull();
        verifyNoInteractions(supplierJpaRepositoryAdapter, categoryJpaRepositoryAdapter);
    }

    @Test
    void elProveedorYLaCategoriaSeResuelvenALaEntidadGestionada() {
        UUID supplierId = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        SupplierEntity supplier = new SupplierEntity();
        CategoryEntity category = new CategoryEntity();
        model.setSupplierId(supplierId);
        model.setCategoryId(categoryId);
        when(supplierJpaRepositoryAdapter.findById(supplierId)).thenReturn(Optional.of(supplier));
        when(categoryJpaRepositoryAdapter.findById(categoryId)).thenReturn(Optional.of(category));

        repository.save(model);

        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productJpaRepositoryAdapter).save(captor.capture());
        assertThat(captor.getValue().getSupplier()).isSameAs(supplier);
        assertThat(captor.getValue().getCategory()).isSameAs(category);
    }

    @Test
    void unProveedorInexistenteNoSeGuardaComoProductoSinProveedor() {
        // Antes se resolvía con orElse(null): el producto se guardaba sin proveedor y nadie se enteraba
        // de que la referencia estaba mal. Un id que no existe es un error de datos, no «sin relación».
        UUID supplierId = UUID.randomUUID();
        model.setSupplierId(supplierId);
        when(supplierJpaRepositoryAdapter.findById(supplierId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> repository.save(model)).isInstanceOf(NotFoundException.class);

        verify(productJpaRepositoryAdapter, never()).save(any());
    }

    /* ==================== save: colecciones anidadas ==================== */

    @Test
    void unaImagenSinRolNiEstadoDeEspejoRecibeLosValoresPorDefecto() {
        model.setImages(new ArrayList<>(List.of(ProductImage.builder().position(3).sourceUrl("http://o/1.jpg").build())));

        ProductEntity saved = saveAndCapture();

        assertThat(saved.getImages()).hasSize(1);
        ProductImageEntity img = saved.getImages().get(0);
        assertThat(img.getRole()).isEqualTo("GALLERY");
        assertThat(img.getMirrorStatus()).isEqualTo(MirrorStatus.PENDING);
        assertThat(img.getPosition()).isEqualTo(3);
        assertThat(img.getProduct()).isSameAs(saved);
    }

    @Test
    void elRolYElEstadoDeEspejoExplicitosSeRespetan() {
        model.setImages(new ArrayList<>(List.of(ProductImage.builder().role("MAIN").sourceUrl("http://o/1.jpg")
                .cdnUrl("http://cdn/1.jpg").mirrorStatus(MirrorStatus.MIRRORED).build())));

        ProductEntity saved = saveAndCapture();

        assertThat(saved.getImages().get(0).getRole()).isEqualTo("MAIN");
        assertThat(saved.getImages().get(0).getMirrorStatus()).isEqualTo(MirrorStatus.MIRRORED);
        assertThat(saved.getImages().get(0).getCdnUrl()).isEqualTo("http://cdn/1.jpg");
    }

    @Test
    void guardarConColeccionesNulasVaciaLasDeLaEntidadEnVezDeConservarlas() {
        UUID id = UUID.randomUUID();
        model.setId(id);
        model.setImages(null);
        model.setVariants(null);
        model.setVariantOptions(null);
        model.setTranslations(null);
        ProductEntity existing = new ProductEntity();
        existing.setId(id);
        existing.getImages().add(ProductImageEntity.builder().sourceUrl("vieja").build());
        when(productJpaRepositoryAdapter.findById(id)).thenReturn(Optional.of(existing));

        repository.save(model);

        // La entidad gestionada es el espejo del modelo: lo que no viene en el modelo deja de existir.
        assertThat(existing.getImages()).isEmpty();
        assertThat(existing.getVariants()).isEmpty();
        assertThat(existing.getVariantOptions()).isEmpty();
        assertThat(existing.getTranslations()).isEmpty();
    }

    @Test
    void lasOpcionesDeVarianteSeReconstruyenConSusValoresAnidados() {
        VariantValue rojo = VariantValue.builder().valueZh("红").value("Rojo").position(0)
                .imageSourceUrl("http://o/rojo.jpg").build();
        model.setVariantOptions(new ArrayList<>(List.of(VariantOption.builder().nameZh("颜色").name("Color").position(1)
                .values(new ArrayList<>(List.of(rojo))).build())));

        ProductEntity saved = saveAndCapture();

        assertThat(saved.getVariantOptions()).hasSize(1);
        assertThat(saved.getVariantOptions().get(0).getName()).isEqualTo("Color");
        assertThat(saved.getVariantOptions().get(0).getProduct()).isSameAs(saved);
        assertThat(saved.getVariantOptions().get(0).getValues()).hasSize(1);
        assertThat(saved.getVariantOptions().get(0).getValues().get(0).getValue()).isEqualTo("Rojo");
        assertThat(saved.getVariantOptions().get(0).getValues().get(0).getOption())
                .isSameAs(saved.getVariantOptions().get(0));
    }

    @Test
    void unaOpcionSinValoresSeGuardaVaciaSinFallar() {
        model.setVariantOptions(new ArrayList<>(List.of(VariantOption.builder().name("Talla").values(null).build())));

        ProductEntity saved = saveAndCapture();

        assertThat(saved.getVariantOptions().get(0).getValues()).isEmpty();
    }

    @Test
    void lasVariantesConservanPrecioStockYEjesDeOpcion() {
        model.setVariants(new ArrayList<>(List.of(ProductVariant.builder().externalId("SKU-1").sku("SKU-1")
                .title("Rojo / M").price(new BigDecimal("12.50")).stock(7).active(true)
                .options(Map.of("Color", "Rojo")).imageCdnUrl("http://cdn/v.jpg").build())));

        ProductEntity saved = saveAndCapture();

        assertThat(saved.getVariants()).hasSize(1);
        assertThat(saved.getVariants().get(0).getPrice()).isEqualByComparingTo("12.50");
        assertThat(saved.getVariants().get(0).getStock()).isEqualTo(7);
        assertThat(saved.getVariants().get(0).getOptions()).containsEntry("Color", "Rojo");
        assertThat(saved.getVariants().get(0).isActive()).isTrue();
        assertThat(saved.getVariants().get(0).getProduct()).isSameAs(saved);
    }

    @Test
    void lasTraduccionesSeReconstruyenPorIdioma() {
        model.setTranslations(new ArrayList<>(List.of(
                ProductTranslation.builder().language("es").title("Camisa").description("desc").provider("ai").build(),
                ProductTranslation.builder().language("en").title("Shirt").build())));

        ProductEntity saved = saveAndCapture();

        assertThat(saved.getTranslations()).hasSize(2);
        assertThat(saved.getTranslations().get(0).getLanguage()).isEqualTo("es");
        assertThat(saved.getTranslations().get(0).getProvider()).isEqualTo("ai");
        assertThat(saved.getTranslations().get(1).getTitle()).isEqualTo("Shirt");
    }

    /* ==================== escalera de precios ==================== */

    @Test
    void sinTramosDeclaradosLaEscaleraDePreciosNoSeToca() {
        model.setPriceTiers(null);

        repository.save(model);

        // priceTiers null = "no informado": borrar los tramos existentes perdería el precio por volumen.
        verify(priceTierRepository, never()).delete(any());
        verify(priceTierRepository, never()).save(any());
    }

    @Test
    void guardarTramosReemplazaLosAnterioresYLaMonedaPorDefectoEsCny() {
        model.setPriceTiers(new ArrayList<>(List.of(
                ProductPriceTier.builder().minQty(1).maxQty(9).unitPrice(new BigDecimal("10")).build(),
                ProductPriceTier.builder().minQty(10).unitPrice(new BigDecimal("8")).currency("USD").build())));
        ProductPriceTierEntity antiguo = ProductPriceTierEntity.builder().minQty(1).build();
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(any())).thenReturn(List.of(antiguo));

        repository.save(model);

        verify(priceTierRepository).delete(antiguo);
        ArgumentCaptor<ProductPriceTierEntity> captor = ArgumentCaptor.forClass(ProductPriceTierEntity.class);
        verify(priceTierRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues().get(0).getCurrency()).isEqualTo("CNY");
        assertThat(captor.getAllValues().get(1).getCurrency()).isEqualTo("USD");
        assertThat(captor.getAllValues().get(0).getMaxQty()).isEqualTo(9);
        assertThat(captor.getAllValues().get(1).getMaxQty()).isNull();
    }

    @Test
    void alLeerUnProductoSeLeAdjuntaSuEscaleraDePrecios() {
        UUID id = UUID.randomUUID();
        ProductEntity entity = new ProductEntity();
        entity.setId(id);
        ProductPriceTierEntity tier = ProductPriceTierEntity.builder().minQty(5).build();
        List<ProductPriceTier> tiers = List.of(ProductPriceTier.builder().minQty(5).build());
        when(productJpaRepositoryAdapter.findById(id)).thenReturn(Optional.of(entity));
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(id)).thenReturn(List.of(tier));
        when(productEntityMapper.toPriceTierDomainList(List.of(tier))).thenReturn(tiers);

        Product found = repository.getById(id);

        assertThat(found.getPriceTiers()).isEqualTo(tiers);
    }

    @Test
    void unProductoInexistenteSeLeeComoNulo() {
        UUID id = UUID.randomUUID();
        when(productJpaRepositoryAdapter.findById(id)).thenReturn(Optional.empty());

        assertThat(repository.getById(id)).isNull();
        verifyNoInteractions(priceTierRepository);
    }

    /* ==================== lecturas y delegaciones ==================== */

    @Test
    void actualizarEsElMismoCaminoQueGuardar() {
        UUID id = UUID.randomUUID();
        model.setId(id);
        ProductEntity existing = new ProductEntity();
        existing.setId(id);
        when(productJpaRepositoryAdapter.findById(id)).thenReturn(Optional.of(existing));

        repository.update(model);

        verify(productEntityMapper).updateEntity(existing, model);
        verify(productJpaRepositoryAdapter).save(existing);
    }

    @Test
    void existirYBorrarDeleganEnElAdaptadorJpa() {
        UUID id = UUID.randomUUID();
        when(productJpaRepositoryAdapter.existsById(id)).thenReturn(true);

        assertThat(repository.existsById(id)).isTrue();
        repository.delete(id);
        verify(productJpaRepositoryAdapter).deleteById(id);
    }

    @Test
    void losBuscadoresPorClaveNaturalDevuelvenVacioSiNoHayCoincidencia() {
        when(productJpaRepositoryAdapter.findBySlug("x")).thenReturn(Optional.empty());
        when(productJpaRepositoryAdapter.findBySourceAndExternalId("1688", "x")).thenReturn(Optional.empty());
        when(productJpaRepositoryAdapter.findFirstByExternalId("x")).thenReturn(Optional.empty());
        when(productJpaRepositoryAdapter.findWithDetailsBySlug("x")).thenReturn(Optional.empty());

        assertThat(repository.findBySlug("x")).isEmpty();
        assertThat(repository.findBySourceAndExternalId("1688", "x")).isEmpty();
        assertThat(repository.findFirstByExternalId("x")).isEmpty();
        assertThat(repository.findWithDetailsBySlug("x")).isEmpty();
    }

    @Test
    void laFichaCompletaPorSlugTambienTraeLosTramosDePrecio() {
        UUID id = UUID.randomUUID();
        ProductEntity entity = new ProductEntity();
        entity.setId(id);
        ProductPriceTierEntity tier = ProductPriceTierEntity.builder().minQty(2).build();
        List<ProductPriceTier> tiers = List.of(ProductPriceTier.builder().minQty(2).build());
        when(productJpaRepositoryAdapter.findWithDetailsBySlug("camisa-lino")).thenReturn(Optional.of(entity));
        when(productJpaRepositoryAdapter.findWithDetailsById(id)).thenReturn(Optional.of(entity));
        when(priceTierRepository.findByProductIdOrderByMinQtyAsc(id)).thenReturn(List.of(tier));
        when(productEntityMapper.toPriceTierDomainList(List.of(tier))).thenReturn(tiers);

        assertThat(repository.findWithDetailsBySlug("camisa-lino")).get()
                .extracting(Product::getPriceTiers).isEqualTo(tiers);
        assertThat(repository.findWithDetailsById(id)).get().extracting(Product::getPriceTiers).isEqualTo(tiers);
    }

    @Test
    void lasBusquedasPaginadasMapeanCadaEntidadDeLaPagina() {
        Pageable pageable = PageRequest.of(0, 10);
        Page<ProductEntity> page = new PageImpl<>(List.of(new ProductEntity()), pageable, 1);
        when(productJpaRepositoryAdapter.findByStatus(ProductStatus.ACTIVE, pageable)).thenReturn(page);
        when(productJpaRepositoryAdapter.findAll(pageable)).thenReturn(page);
        when(productJpaRepositoryAdapter.findTopByTrendScore(ProductStatus.ACTIVE, pageable)).thenReturn(page);
        UUID categoryId = UUID.randomUUID();
        when(productJpaRepositoryAdapter.findByCategoryOrderByTrend(categoryId, ProductStatus.ACTIVE, pageable))
                .thenReturn(page);

        assertThat(repository.findByStatus(ProductStatus.ACTIVE, pageable).getContent()).hasSize(1);
        assertThat(repository.findAll(pageable).getContent()).hasSize(1);
        assertThat(repository.findTopByTrendScore(ProductStatus.ACTIVE, pageable).getContent()).hasSize(1);
        assertThat(repository.findByCategoryOrderByTrend(categoryId, ProductStatus.ACTIVE, pageable).getContent())
                .hasSize(1);
    }

    @Test
    void losListadosCompletosPasanPorElMapeoEnBloque() {
        List<ProductEntity> entities = List.of(new ProductEntity());
        List<Product> domain = List.of(Product.builder().build());
        List<UUID> ids = List.of(UUID.randomUUID());
        when(productJpaRepositoryAdapter.findAll()).thenReturn(entities);
        when(productJpaRepositoryAdapter.findAllById(ids)).thenReturn(entities);
        when(productEntityMapper.toDomainList(entities)).thenReturn(domain);

        assertThat(repository.findAll()).isEqualTo(domain);
        assertThat(repository.findAllById(ids)).isEqualTo(domain);
    }

    /** Guarda el modelo y devuelve la entidad que se envió al adaptador JPA. */
    private ProductEntity saveAndCapture() {
        repository.save(model);
        ArgumentCaptor<ProductEntity> captor = ArgumentCaptor.forClass(ProductEntity.class);
        verify(productJpaRepositoryAdapter).save(captor.capture());
        return captor.getValue();
    }
}
