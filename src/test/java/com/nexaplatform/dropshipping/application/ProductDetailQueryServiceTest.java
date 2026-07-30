package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.ProductDetailQueryService;
import com.nexaplatform.dropshipping.domain.enums.ProductStatus;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductAttributeEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductSpecificationEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductAttributeRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductSpecificationRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductTagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

/**
 * Consultas de la ficha de producto, extraídas del controlador del escaparate.
 *
 * <p>Además de mover las reglas fuera de la capa HTTP, se protege que "productos relacionados" NO cargue
 * el catálogo entero: se resolvía con findAll() sobre casi 4.000 productos para devolver ocho, en una
 * página que ve cada visitante.
 */
class ProductDetailQueryServiceTest {

    private ProductRepository products;
    private ProductSpecificationRepository specs;
    private ProductAttributeRepository attributes;
    private ProductDetailQueryService service;
    private ProductEntity base;
    private final UUID categoryId = UUID.randomUUID();

    private static ProductEntity product(UUID category, double trend) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        p.setStatus(ProductStatus.ACTIVE);
        p.setTrendScore(BigDecimal.valueOf(trend));
        if (category != null) {
            CategoryEntity c = new CategoryEntity();
            c.setId(category);
            p.setCategory(c);
        }
        return p;
    }

    @BeforeEach
    void setUp() {
        products = mock(ProductRepository.class);
        specs = mock(ProductSpecificationRepository.class);
        attributes = mock(ProductAttributeRepository.class);
        service = new ProductDetailQueryService(products, specs, attributes,
                mock(ProductTagRepository.class));
        base = product(categoryId, 1.0);
        lenient().when(products.findById(base.getId())).thenReturn(Optional.of(base));
    }

    @Test
    void losRelacionadosSeConsultanPorCategoriaSinCargarTodoElCatalogo() {
        when(products.findByCategoryIdAndStatus(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(base, product(categoryId, 5.0), product(categoryId, 9.0))));

        List<ProductEntity> related = service.relatedProducts(base.getId(), 8);

        assertThat(related).hasSize(2);
        verify(products, never()).findAll();
    }

    @Test
    void losRelacionadosVienenOrdenadosPorTendenciaYSinElPropioProducto() {
        ProductEntity flojo = product(categoryId, 2.0);
        ProductEntity fuerte = product(categoryId, 9.0);
        when(products.findByCategoryIdAndStatus(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(flojo, base, fuerte)));

        List<ProductEntity> related = service.relatedProducts(base.getId(), 8);

        assertThat(related).containsExactly(fuerte, flojo);
        assertThat(related).doesNotContain(base);
    }

    @Test
    void unProductoSinCategoriaCaeALosVisiblesEnLugarDeQuedarseSinRelacionados() {
        ProductEntity huerfano = product(null, 1.0);
        when(products.findById(huerfano.getId())).thenReturn(Optional.of(huerfano));
        when(products.findVisibleByStatus(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product(categoryId, 3.0))));

        assertThat(service.relatedProducts(huerfano.getId(), 8)).hasSize(1);
    }

    @Test
    void lasEspecificacionesCaenDelIdiomaPedidoAInglesYLuegoALasNeutrales() {
        UUID id = base.getId();
        ProductSpecificationEntity neutral = new ProductSpecificationEntity();
        when(specs.findByProduct_IdAndLocaleOrderByPositionAsc(id, "de")).thenReturn(List.of());
        when(specs.findByProduct_IdAndLocaleOrderByPositionAsc(id, "en")).thenReturn(List.of());
        when(specs.findByProduct_IdOrderByPositionAsc(id)).thenReturn(List.of(neutral));

        // Sin la cascada, un producto no traducido se quedaría sin ficha técnica.
        assertThat(service.specifications(id, "de")).containsExactly(neutral);
    }

    @Test
    void elAtributoTraducidoPisaAlNeutralPeroNoSePierdenLosQueSoloSonNeutrales() {
        UUID id = base.getId();
        ProductAttributeEntity color = new ProductAttributeEntity();
        color.setAttrKey("color"); color.setAttrValue("Rojo"); color.setLocale(null);
        ProductAttributeEntity colorEn = new ProductAttributeEntity();
        colorEn.setAttrKey("color"); colorEn.setAttrValue("Red"); colorEn.setLocale("en");
        ProductAttributeEntity soloNeutral = new ProductAttributeEntity();
        soloNeutral.setAttrKey("peso"); soloNeutral.setAttrValue("300 g"); soloNeutral.setLocale(null);
        when(attributes.findByProduct_Id(id)).thenReturn(List.of(color, colorEn, soloNeutral));

        Map<String, String> result = service.attributes(id, "en");

        assertThat(result).containsEntry("color", "Red").containsEntry("peso", "300 g");
    }
}
