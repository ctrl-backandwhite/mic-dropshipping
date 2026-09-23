package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.api.dto.SavedCartItemDto;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.SavedCartItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.SavedCartItemJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Reglas de "guardar para más tarde": guardar suma cantidades y refresca el snapshot, el merge sube
 * varias ignorando nulos, y una referencia a producto inexistente se rechaza.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SavedCartServiceTest {

    @Mock
    SavedCartItemJpaRepository repo;
    @Mock
    ProductRepository productRepository;

    private SavedCartService service;
    private UUID userId;
    private UUID productId;
    private UUID variantId;

    @BeforeEach
    void setUp() {
        service = new SavedCartService(repo, productRepository);
        userId = UUID.randomUUID();
        productId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        when(productRepository.existsById(any())).thenReturn(true);
        when(repo.findByUserIdOrderByCreatedAtDesc(any())).thenReturn(List.of());
    }

    private SavedCartItemDto dto(UUID product, UUID variant, int qty) {
        return new SavedCartItemDto(product, variant, "SKU1", "camisa-roja", "Camisa roja", "http://img/1.jpg",
                "Color: Rojo", new BigDecimal("10.00"), "EUR", qty, 2, new BigDecimal("12.00"), "EUR", "€");
    }

    @Test
    void guardarUnaLineaNuevaLaPersisteConSuCantidadYUsuario() {
        when(repo.findByUserIdAndProductIdAndVariantId(userId, productId, variantId)).thenReturn(Optional.empty());

        service.upsert(userId, dto(productId, variantId, 3));

        ArgumentCaptor<SavedCartItemEntity> captor = ArgumentCaptor.forClass(SavedCartItemEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(userId);
        assertThat(captor.getValue().getProductId()).isEqualTo(productId);
        assertThat(captor.getValue().getVariantId()).isEqualTo(variantId);
        assertThat(captor.getValue().getQuantity()).isEqualTo(3);
        assertThat(captor.getValue().getTitle()).isEqualTo("Camisa roja");
    }

    @Test
    void guardarUnaLineaYaExistenteSumaLaCantidadYRefrescaElSnapshot() {
        SavedCartItemEntity existing = SavedCartItemEntity.builder().userId(userId).productId(productId)
                .variantId(variantId).quantity(2).title("viejo").build();
        when(repo.findByUserIdAndProductIdAndVariantId(userId, productId, variantId)).thenReturn(Optional.of(existing));

        service.upsert(userId, dto(productId, variantId, 3));

        verify(repo).save(existing);
        assertThat(existing.getQuantity()).isEqualTo(5);
        assertThat(existing.getTitle()).isEqualTo("Camisa roja"); // snapshot al más reciente
    }

    @Test
    void guardarUnProductoInexistenteFallaYNoPersiste() {
        when(productRepository.existsById(productId)).thenReturn(false);

        assertThatThrownBy(() -> service.upsert(userId, dto(productId, variantId, 1)))
                .isInstanceOf(NotFoundException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void elMergeSubeCadaLineaValidaEIgnoraLosNulos() {
        when(repo.findByUserIdAndProductIdAndVariantId(any(), any(), any())).thenReturn(Optional.empty());

        service.merge(userId, Arrays.asList(dto(productId, null, 1), null, dto(UUID.randomUUID(), variantId, 2)));

        verify(repo, times(2)).save(any());
    }

    @Test
    void quitarUnaLineaBorraPorUsuarioProductoYVariante() {
        service.remove(userId, productId, variantId);

        verify(repo).deleteByUserIdAndProductIdAndVariantId(userId, productId, variantId);
    }

    @Test
    void listarMapeaLasEntidadesAsuDto() {
        SavedCartItemEntity entity = SavedCartItemEntity.builder().userId(userId).productId(productId)
                .variantId(variantId).quantity(4).slug("camisa-roja").title("Camisa roja")
                .unitPriceSource(new BigDecimal("10.00")).sourceCurrency("EUR").build();
        when(repo.findByUserIdOrderByCreatedAtDesc(userId)).thenReturn(List.of(entity));

        List<SavedCartItemDto> out = service.list(userId);

        assertThat(out).hasSize(1);
        assertThat(out.get(0).productId()).isEqualTo(productId);
        assertThat(out.get(0).quantity()).isEqualTo(4);
        assertThat(out.get(0).title()).isEqualTo("Camisa roja");
    }
}
