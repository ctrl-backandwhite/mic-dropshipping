package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.dto.CatalogDtos.LivePromotionView;
import com.nexaplatform.dropshipping.api.dto.CatalogDtos.ProductSummaryView;
import com.nexaplatform.dropshipping.api.dto.PageResponse;
import com.nexaplatform.dropshipping.api.mapper.CatalogStorefrontReadService;
import com.nexaplatform.dropshipping.application.service.PromotionShowcaseService;
import com.nexaplatform.dropshipping.domain.enums.PromotionKind;
import com.nexaplatform.dropshipping.domain.enums.PromotionScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionTargetEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionTargetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El cartel de rebajas de la portada: qué se anuncia y qué no. */
class PromotionShowcaseServiceTest {

    private PromotionRepository promotionRepository;
    private PromotionTargetRepository targetRepository;
    private CatalogStorefrontReadService storefrontRead;
    private PromotionShowcaseService service;

    @BeforeEach
    void setUp() {
        promotionRepository = mock(PromotionRepository.class);
        targetRepository = mock(PromotionTargetRepository.class);
        storefrontRead = mock(CatalogStorefrontReadService.class);
        service = new PromotionShowcaseService(promotionRepository, targetRepository, storefrontRead);

        lenient().when(storefrontRead.productList(anyInt(), anyInt(), anyString(), any(), any(), any(), any(), any(),
                anyString())).thenReturn(pagina(2));
        lenient().when(storefrontRead.favorites(any(), anyInt(), anyInt(), anyString())).thenReturn(pagina(1));
    }

    private static PageResponse<ProductSummaryView> pagina(int cuantos) {
        List<ProductSummaryView> items = new java.util.ArrayList<>();
        for (int i = 0; i < cuantos; i++) {
            items.add(new ProductSummaryView(UUID.randomUUID(), "slug-" + i, "Producto " + i, null, null, null, null,
                    0, 0, null, null, null, null, null, null, null, null, null, false));
        }
        return new PageResponse<>(items, 0, cuantos, cuantos, 1);
    }

    private static PromotionEntity rebaja(String nombre, int porcentaje, PromotionScope alcance) {
        return PromotionEntity.builder().id(UUID.randomUUID()).name(nombre).kind(PromotionKind.SEASONAL)
                .scope(alcance).percentOff(BigDecimal.valueOf(porcentaje)).active(true)
                .createdAt(Instant.now()).build();
    }

    @Test
    @DisplayName("un cupón no se anuncia en la portada aunque esté vivo")
    void cuponNoSeAnuncia() {
        PromotionEntity cupon = rebaja("Bienvenida", 25, PromotionScope.ALL);
        cupon.setCode("BIENVENIDA");
        when(promotionRepository.findAll()).thenReturn(List.of(cupon));

        assertThat(service.live("es")).isEmpty();
    }

    @Test
    @DisplayName("una promoción desactivada o fuera de fechas no sale")
    void soloLasVivas() {
        PromotionEntity apagada = rebaja("Apagada", 40, PromotionScope.ALL);
        apagada.setActive(false);
        PromotionEntity caducada = rebaja("Caducada", 50, PromotionScope.ALL);
        caducada.setEndsAt(Instant.now().minus(1, ChronoUnit.DAYS));
        PromotionEntity viva = rebaja("Invierno", 30, PromotionScope.ALL);
        when(promotionRepository.findAll()).thenReturn(List.of(apagada, caducada, viva));

        List<LivePromotionView> out = service.live("es");

        assertThat(out).singleElement().extracting(LivePromotionView::name).isEqualTo("Invierno");
    }

    @Test
    @DisplayName("se ordenan de mayor a menor descuento")
    void ordenPorDescuento() {
        when(promotionRepository.findAll())
                .thenReturn(List.of(rebaja("Floja", 10, PromotionScope.ALL), rebaja("Fuerte", 40, PromotionScope.ALL)));

        assertThat(service.live("es")).extracting(LivePromotionView::name).containsExactly("Fuerte", "Floja");
    }

    @Test
    @DisplayName("una promoción de productos concretos trae solo esos productos")
    void alcanceDeProductos() {
        PromotionEntity p = rebaja("Selección", 20, PromotionScope.PRODUCT);
        UUID producto = UUID.randomUUID();
        when(promotionRepository.findAll()).thenReturn(List.of(p));
        when(targetRepository.findByPromotionId(p.getId()))
                .thenReturn(List.of(PromotionTargetEntity.builder().productId(producto).build()));

        List<LivePromotionView> out = service.live("es");

        assertThat(out).singleElement().extracting(v -> v.products().size()).isEqualTo(1);
        verify(storefrontRead).favorites(eq(List.of(producto)), eq(0), anyInt(), eq("es"));
    }

    @Test
    @DisplayName("una promoción de categoría sin categoría apuntada no pide productos al catálogo")
    void categoriaHuerfana() {
        PromotionEntity p = rebaja("Rota", 20, PromotionScope.CATEGORY);
        when(promotionRepository.findAll()).thenReturn(List.of(p));
        when(targetRepository.findByPromotionId(p.getId())).thenReturn(List.of());

        assertThat(service.live("es")).singleElement().extracting(v -> v.products()).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).isEmpty();
        verify(storefrontRead, never()).productList(anyInt(), anyInt(), anyString(), any(), any(), any(), any(), any(),
                anyString());
    }

    @Test
    @DisplayName("si el catálogo falla, la rebaja se anuncia igual pero sin fotos")
    void catalogoCaidoNoTumbaElBanner() {
        when(promotionRepository.findAll()).thenReturn(List.of(rebaja("Invierno", 30, PromotionScope.ALL)));
        when(storefrontRead.productList(anyInt(), anyInt(), anyString(), any(), any(), any(), any(), any(),
                anyString())).thenThrow(new IllegalStateException("catálogo caído"));

        List<LivePromotionView> out = service.live("es");

        assertThat(out).singleElement().satisfies(v -> {
            assertThat(v.name()).isEqualTo("Invierno");
            assertThat(v.products()).isEmpty();
        });
    }
}
