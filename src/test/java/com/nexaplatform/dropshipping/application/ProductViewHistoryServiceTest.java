package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.ProductViewHistoryService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductViewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Registro y lectura del historial de fichas visitadas. */
@ExtendWith(MockitoExtension.class)
@DisplayName("Historial de visitas · registro, lectura y retención")
class ProductViewHistoryServiceTest {

    @Mock
    ProductViewRepository viewRepository;
    @Mock
    ProductRepository productRepository;

    @InjectMocks
    ProductViewHistoryService service;

    @Test
    @DisplayName("registrar una visita anota usuario, producto y momento")
    void registrarUnaVisitaAnotaElMomento() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.existsById(productId)).thenReturn(true);
        Instant antes = Instant.now();

        service.record(userId, productId);

        ArgumentCaptor<Instant> momento = ArgumentCaptor.forClass(Instant.class);
        verify(viewRepository).registrarVisita(eq(userId), eq(productId), momento.capture());
        assertThat(momento.getValue()).isBetween(antes.minusSeconds(5), Instant.now().plusSeconds(5));
    }

    /**
     * Sin esta comprobación cualquiera podría sembrar su historial con identificadores inventados, que
     * luego nadie sabría pintar ni en la página ni en el correo.
     */
    @Test
    @DisplayName("no se registra la visita a un producto que no existe")
    void noSeRegistraUnProductoInexistente() {
        UUID productId = UUID.randomUUID();
        when(productRepository.existsById(productId)).thenReturn(false);

        assertThatThrownBy(() -> service.record(UUID.randomUUID(), productId))
                .isInstanceOf(NotFoundException.class);

        verify(viewRepository, never()).registrarVisita(any(), any(), any());
    }

    /**
     * El historial de quien lleva meses mirando puede ser de miles de filas y nadie baja tan abajo: se pide
     * acotado para no cargar el catálogo entero de esa persona y enseñar las primeras veinticuatro.
     */
    @Test
    @DisplayName("el historial se lee acotado, no entero")
    void elHistorialSeLeeAcotado() {
        UUID userId = UUID.randomUUID();
        UUID visto = UUID.randomUUID();
        when(viewRepository.findProductIdsByUserId(eq(userId), any())).thenReturn(List.of(visto));

        assertThat(service.viewedProductIds(userId)).containsExactly(visto);

        verify(viewRepository).findProductIdsByUserId(eq(userId),
                eq(Limit.of(ProductViewHistoryService.MAX_HISTORIAL)));
    }

    /** La retención acordada con el dueño del producto: 90 días, ni el historial ni el correo más allá. */
    @Test
    @DisplayName("la purga borra lo anterior a los 90 días")
    void laPurgaBorraLoAnteriorALosNoventaDias() {
        when(viewRepository.deleteByViewedAtBefore(any(Instant.class))).thenReturn(7);
        Instant ahora = Instant.now();

        assertThat(service.purgeExpired()).isEqualTo(7);

        ArgumentCaptor<Instant> limite = ArgumentCaptor.forClass(Instant.class);
        verify(viewRepository).deleteByViewedAtBefore(limite.capture());
        Duration retencion = Duration.between(limite.getValue(), ahora);
        assertThat(retencion).isBetween(Duration.ofDays(90).minusMinutes(1), Duration.ofDays(90).plusMinutes(1));
    }
}
