package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentFailure;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

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
    private OpsAlertService opsAlertService;
    private NotificationUseCase notificationUseCase;
    private OrderShipmentRepository shipmentRepository;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        provider = mock(FulfillmentProvider.class);
        opsAlertService = mock(OpsAlertService.class);
        notificationUseCase = mock(NotificationUseCase.class);
        shipmentRepository = mock(OrderShipmentRepository.class);
        service = new FulfillmentService(orderRepository, mock(OrderTrackingEventRepository.class),
                provider, mock(UserRepository.class), mock(OrderEmailService.class),
                new ObjectMapper(), new YunExpressEventCipher(), opsAlertService, notificationUseCase, shipmentRepository,
                mock(TrackingViewMapper.class));

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
        when(provider.createShipments(any(Order.class))).thenThrow(FulfillmentFailure.from(
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
        when(provider.createShipments(any(Order.class)))
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

        verify(provider, never()).createShipments(any(Order.class));
    }

    @Test
    void unEnvioYaAbandonadoNoSeReintentaSolo() {
        order.setFulfillmentFailedAt(Instant.now());

        service.createShipment(order.getId());

        verify(provider, never()).createShipments(any(Order.class));
    }

    @Test
    void alTercerFalloTransitorioSeDejaParaElAdminYSeAvisa() {
        when(provider.createShipments(any(Order.class)))
                .thenThrow(new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT, "Service execution time-out"));

        for (int i = 0; i < 3; i++) {
            order.setFulfillmentNextAttemptAt(null); // simula que la espera de 10 min ya venció
            service.createShipment(order.getId());
        }

        assertThat(order.getFulfillmentAttempts()).isEqualTo(3);
        assertThat(order.getFulfillmentFailedAt()).isNotNull();
        verify(provider, times(3)).createShipments(any(Order.class));
        // Aviso por correo Y en la bandeja del panel: que no dependa de que alguien lea el buzón.
        verify(opsAlertService).fulfillmentFailed(eq("NX-1784936692-7159"), any(), eq(3), any());
        verify(notificationUseCase).sendAdminNotification(any(), contains("NX-1784936692-7159"), any());
    }

    @Test
    void entreIntentosSeEsperanDiezMinutos() {
        when(provider.createShipments(any(Order.class)))
                .thenThrow(new FulfillmentFailure(FulfillmentFailure.Kind.TRANSIENT, "Service execution time-out"));

        service.createShipment(order.getId());

        assertThat(order.getFulfillmentNextAttemptAt())
                .isAfter(Instant.now().plus(Duration.ofMinutes(9)))
                .isBefore(Instant.now().plus(Duration.ofMinutes(11)));
    }

    @Test
    void cuandoElEnvioSeCreaSeBorraElRastroDeLosIntentosFallidos() {
        order.setFulfillmentAttempts(3);
        order.setFulfillmentError("Service execution time-out");
        order.setFulfillmentNextAttemptAt(null);
        when(provider.createShipments(any(Order.class))).thenReturn(List.of(
                new FulfillmentResult("Standard Shipping", "YT2621101299000012", "YT2621101299000012", 15)));

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
