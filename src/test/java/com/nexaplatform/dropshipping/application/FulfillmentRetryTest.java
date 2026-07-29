package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentFailure;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tolerancia a fallo al crear el envío en el transportista.
 *
 * <p>Antes, cualquier fallo se reintentaba cada 60 s indefinidamente y el único rastro era una línea de
 * log: un pedido rechazado por peso se quedaba en {@code FORWARDED} sin guía y sin que nadie se enterase.
 * Lo que se fija aquí es que un fallo <b>permanente</b> se abandone al instante con el motivo guardado, que
 * uno <b>transitorio</b> se reintente con espera creciente, y que el pedido no se martillee mientras esa
 * espera corre.
 */
class FulfillmentRetryTest {

    private OrderRepository orderRepository;
    private FulfillmentProvider provider;
    private FulfillmentService service;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = Mockito.mock(OrderRepository.class);
        provider = Mockito.mock(FulfillmentProvider.class);
        service = new FulfillmentService(orderRepository, Mockito.mock(OrderTrackingEventRepository.class),
                provider, Mockito.mock(UserRepository.class), Mockito.mock(OrderEmailService.class),
                new ObjectMapper(), new YunExpressEventCipher());

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-1784936692-7159");
        order.setStatus(OrderStatus.FORWARDED);
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void unFalloPermanenteSeAbandonaAlPrimerIntentoConElMotivoGuardado() {
        // "Order rule verification failed" = el bulto no cabe en el canal. Reintentar no lo arregla.
        when(provider.createShipment(any(Order.class))).thenThrow(FulfillmentFailure.from(
                "YunExpress rechazó el envío: 02039171 Weight should not exceed 2KG"));

        service.createShipment(order.getId());

        assertThat(order.getFulfillmentAttempts()).isEqualTo(1);
        assertThat(order.getFulfillmentFailedAt()).isNotNull();
        assertThat(order.getFulfillmentNextAttemptAt()).isNull();
        assertThat(order.getFulfillmentError()).contains("02039171");
        assertThat(order.getTrackingNumber()).isNull();
    }

    @Test
    void unFalloTransitorioProgramaOtroIntento() {
        when(provider.createShipment(any(Order.class)))
                .thenThrow(new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT, "Service execution time-out"));

        service.createShipment(order.getId());

        assertThat(order.getFulfillmentAttempts()).isEqualTo(1);
        assertThat(order.getFulfillmentFailedAt()).isNull();
        assertThat(order.getFulfillmentNextAttemptAt()).isAfter(Instant.now());
    }

    @Test
    void mientrasCorreLaEsperaNoSeVuelveALlamarAlTransportista() {
        order.setFulfillmentAttempts(2);
        order.setFulfillmentNextAttemptAt(Instant.now().plus(Duration.ofMinutes(10)));

        service.createShipment(order.getId());

        verify(provider, never()).createShipment(any(Order.class));
    }

    @Test
    void unEnvioYaAbandonadoNoSeReintentaSolo() {
        order.setFulfillmentFailedAt(Instant.now());

        service.createShipment(order.getId());

        verify(provider, never()).createShipment(any(Order.class));
    }

    @Test
    void trasVariosFallosTransitoriosSeAcabaAbandonando() {
        when(provider.createShipment(any(Order.class)))
                .thenThrow(new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT, "Service execution time-out"));

        for (int i = 0; i < 8; i++) {
            order.setFulfillmentNextAttemptAt(null); // simula que la espera ya venció
            service.createShipment(order.getId());
        }

        assertThat(order.getFulfillmentAttempts()).isEqualTo(8);
        assertThat(order.getFulfillmentFailedAt()).isNotNull();
        verify(provider, times(8)).createShipment(any(Order.class));
    }

    @Test
    void cuandoElEnvioSeCreaSeBorraElRastroDeLosIntentosFallidos() {
        order.setFulfillmentAttempts(3);
        order.setFulfillmentError("Service execution time-out");
        order.setFulfillmentNextAttemptAt(null);
        when(provider.createShipment(any(Order.class)))
                .thenReturn(new FulfillmentResult("Standard Shipping", "YT2621101299000012", "YT2621101299000012", 15));

        service.createShipment(order.getId());

        assertThat(order.getTrackingNumber()).isEqualTo("YT2621101299000012");
        assertThat(order.getFulfillmentAttempts()).isZero();
        assertThat(order.getFulfillmentError()).isNull();
        assertThat(order.getFulfillmentFailedAt()).isNull();
    }

    @Test
    void elReintentoManualDelAdminRehabilitaElEnvio() {
        order.setFulfillmentFailedAt(Instant.now());
        order.setFulfillmentAttempts(8);

        service.retryFulfillment(order.getId());

        assertThat(order.getFulfillmentFailedAt()).isNull();
        assertThat(order.getFulfillmentAttempts()).isZero();
        assertThat(order.getFulfillmentNextAttemptAt()).isNull();
    }

    @Test
    void clasificaLosCodigosDelTransportista() {
        assertThat(FulfillmentFailure.from("... 02039171 Order rule verification failed").isPermanent()).isTrue();
        assertThat(FulfillmentFailure.from("... 02030012 Service execution time-out").isPermanent()).isFalse();
        // Lo desconocido se trata como transitorio: rendirse de más deja envíos sin crear para siempre.
        assertThat(FulfillmentFailure.from("algo que no hemos visto nunca").isPermanent()).isFalse();
        assertThat(FulfillmentFailure.of(new IllegalStateException("fallo de red")).isPermanent()).isFalse();
    }
}
