package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.application.service.PricingService;
import com.nexaplatform.dropshipping.application.service.PricingService.PricedAmount;
import com.nexaplatform.dropshipping.application.service.ProductViewHistoryService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.ProductEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductViewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
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
    @Mock
    PricingService pricingService;

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
        verify(viewRepository).registrarVisita(eq(userId), eq(productId), momento.capture(),
                any(), any(), any());
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

        verify(viewRepository, never()).registrarVisita(any(), any(), any(), any(), any(), any());
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

    /**
     * El historial es una VENTANA de cincuenta fichas: la que entra empuja fuera a la más antigua.
     *
     * <p>Antes el tope era solo de LECTURA —se guardaba todo y se leían las primeras doscientas—, así que
     * la tabla crecía sin fin con filas que nadie iba a mirar y que solo desaparecían al cumplir noventa
     * días. Si esta prueba deja de podar, vuelve a acumularse el rastro entero de cada persona.
     */
    @Test
    @DisplayName("al pasar de cincuenta, la visita nueva empuja fuera a la más antigua")
    void alPasarDeCincuentaSeEmpujaFueraLaMasAntigua() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        when(productRepository.existsById(productId)).thenReturn(true);

        service.record(userId, productId);

        // Primero se anota la visita y DESPUÉS se poda: al revés, la ficha recién abierta podría ser la
        // que se borra cuando el historial está justo en el tope.
        InOrder enOrden = inOrder(viewRepository);
        enOrden.verify(viewRepository).registrarVisita(eq(userId), eq(productId), any(Instant.class),
                any(), any(), any());
        enOrden.verify(viewRepository).podarExcedente(userId, 50);
    }

    /** Cincuenta, el número acordado. Va aquí para que cambiarlo sea una decisión, no un descuido. */
    @Test
    @DisplayName("el historial guarda cincuenta fichas por usuario")
    void elHistorialGuardaCincuentaFichas() {
        assertThat(ProductViewHistoryService.MAX_HISTORIAL).isEqualTo(50);
    }

    /**
     * La visita guarda el precio TAL COMO LO VIO esa persona, ya con todos los cálculos hechos.
     *
     * <p>Es lo que permite pintar el historial sin rehacerlos: por cada una de las cincuenta fichas
     * habría que convertir la divisa, aplicar el margen del país de registro, el IVA, el envío, las dos
     * bolsas de subvención y el recargo fijo. Eso era lo que hacía lenta la página.
     *
     * <p>Y lo calcula el SERVIDOR, no llega del navegador: un importe que viajara desde el cliente lo
     * podría poner cualquiera, y el historial acabaría enseñando el precio que a cada uno le apeteciera.
     */
    @Test
    @DisplayName("la visita guarda el precio que vio el usuario, calculado en el servidor")
    void laVisitaGuardaElPrecioQueVioElUsuario() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ProductEntity producto = new ProductEntity();
        producto.setId(productId);
        when(productRepository.existsById(productId)).thenReturn(true);
        when(productRepository.findById(productId)).thenReturn(java.util.Optional.of(producto));
        when(pricingService.priceFor(producto)).thenReturn(precioDe("28.26", "EUR", "28,26 €"));

        service.record(userId, productId);

        verify(viewRepository).registrarVisita(eq(userId), eq(productId), any(Instant.class),
                eq(new BigDecimal("28.26")), eq("EUR"), eq("28,26 €"));
    }

    /**
     * Si el precio no se puede resolver, la visita se anota IGUAL.
     *
     * <p>El historial sirve para reencontrar un producto; el precio es un dato útil al lado, no el
     * motivo. Dejar de anotar la visita porque falle el cálculo sería perder lo importante por lo
     * accesorio, y el usuario no volvería a encontrar la ficha por la que pasó.
     */
    @Test
    @DisplayName("un fallo al calcular el precio no impide anotar la visita")
    void unFalloAlCalcularElPrecioNoImpideAnotarLaVisita() {
        UUID userId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        ProductEntity producto = new ProductEntity();
        producto.setId(productId);
        when(productRepository.existsById(productId)).thenReturn(true);
        when(productRepository.findById(productId)).thenReturn(java.util.Optional.of(producto));
        when(pricingService.priceFor(producto)).thenThrow(new IllegalStateException("sin tipo de cambio"));

        service.record(userId, productId);

        verify(viewRepository).registrarVisita(eq(userId), eq(productId), any(Instant.class),
                isNull(), isNull(), isNull());
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
    /** Un precio ya resuelto, con lo único que el historial necesita: importe, moneda y su formato. */
    private static PricedAmount precioDe(String importe, String moneda, String formateado) {
        return new PricedAmount(null, null, new BigDecimal(importe), moneda, "€", formateado,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }

}
