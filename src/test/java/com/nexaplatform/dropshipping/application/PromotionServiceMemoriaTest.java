package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.PromotionService;
import com.nexaplatform.dropshipping.domain.enums.PromotionKind;
import com.nexaplatform.dropshipping.domain.enums.PromotionScope;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.CategoryEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.PromotionTargetEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.CategoryRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRedemptionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.PromotionTargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La red que protege el arreglo de rendimiento del 5-sep-2026.
 *
 * <p>Contexto, para que nadie lo deshaga sin querer: el listado del escaparate calcula el precio de
 * cada ficha, y calcular un precio preguntaba a la base de datos qué promociones están vivas. Con el
 * filtro de precio el listado barre 5.000 productos, así que eran 5.000 consultas para devolver 24
 * fichas: 78 segundos medidos en PRE con el catálogo real y CERO promociones dadas de alta. Estas
 * pruebas fijan que esa consulta se hace una vez, no una por producto, y que el panel puede tirar la
 * memoria cuando cambia algo.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PromotionServiceMemoriaTest {

    @Mock
    PromotionRepository promotionRepository;
    @Mock
    PromotionTargetRepository targetRepository;
    @Mock
    CategoryRepository categoryRepository;
    @Mock
    PromotionRedemptionRepository redemptionRepository;

    @InjectMocks
    PromotionService service;

    private static final BigDecimal PRECIO = new BigDecimal("100.00");

    private static ProductEntity producto(UUID categoryId) {
        ProductEntity p = new ProductEntity();
        p.setId(UUID.randomUUID());
        if (categoryId != null) {
            CategoryEntity c = new CategoryEntity();
            c.setId(categoryId);
            p.setCategory(c);
        }
        return p;
    }

    @Test
    void mil_productos_no_son_mil_consultas_de_promociones() {
        when(promotionRepository.findLive(any())).thenReturn(List.of());

        for (int i = 0; i < 1000; i++) {
            service.applyAutomatic(producto(null), PRECIO, null);
        }

        // Una sola vez. Se deja holgura de una segunda por si el reloj cruza la ventana de 5 s
        // mientras corre el bucle; lo que NO puede volver a pasar es que sean mil.
        verify(promotionRepository, atMost(2)).findLive(any());
    }

    @Test
    void los_destinos_de_una_promocion_se_piden_una_vez_por_promocion_no_una_por_producto() {
        UUID categoria = UUID.randomUUID();
        PromotionEntity p = PromotionEntity.builder().id(UUID.randomUUID()).name("Rebajas")
                .percentOff(new BigDecimal("10")).scope(PromotionScope.CATEGORY)
                .kind(PromotionKind.SEASONAL).active(true).createdAt(Instant.now()).build();
        when(promotionRepository.findLive(any())).thenReturn(List.of(p));
        when(targetRepository.findByPromotionId(p.getId()))
                .thenReturn(List.of(PromotionTargetEntity.builder().categoryId(categoria).build()));
        CategoryEntity hoja = new CategoryEntity();
        hoja.setId(categoria);
        when(categoryRepository.findById(categoria)).thenReturn(Optional.of(hoja));

        for (int i = 0; i < 500; i++) {
            assertThat(service.applyAutomatic(producto(categoria), PRECIO, null).applies()).isTrue();
        }

        verify(targetRepository, atMost(2)).findByPromotionId(p.getId());
        // La cadena de ancestros tampoco se reconstruye producto a producto.
        verify(categoryRepository, atMost(2)).findById(categoria);
    }

    @Test
    void invalidar_obliga_a_volver_a_preguntar() {
        when(promotionRepository.findLive(any())).thenReturn(List.of());

        service.applyAutomatic(producto(null), PRECIO, null);
        service.invalidar();
        service.applyAutomatic(producto(null), PRECIO, null);

        verify(promotionRepository, times(2)).findLive(any());
    }
}
