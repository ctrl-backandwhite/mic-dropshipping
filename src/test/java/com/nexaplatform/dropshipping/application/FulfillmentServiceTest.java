package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.exception.NotFoundException;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.TrackingProgress;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingStep;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FulfillmentServiceTest {

    @Mock
    OrderRepository orderRepository;
    @Mock
    OrderTrackingEventRepository trackingRepository;
    @Mock
    FulfillmentProvider cainiao;
    @Mock
    UserRepository userRepository;
    @Mock
    OrderEmailService orderEmailService;
    @Mock
    OrderShipmentRepository shipmentRepository;
    @Mock
    TrackingViewMapper trackingViewMapper;
    @InjectMocks
    FulfillmentService service;

    // Real ObjectMapper is fine: applyPush only parses JSON, no IO/network.
    private final ObjectMapper realMapper = new ObjectMapper();

    private static Order order(OrderStatus status, String trackingNumber) {
        return Order.builder().id(UUID.randomUUID()).orderNumber("NX-1").status(status)
                .trackingNumber(trackingNumber).shippingCountry("ES").build();
    }

    private static OrderTrackingEventEntity event(String status, String description) {
        return OrderTrackingEventEntity.builder().orderId(UUID.randomUUID()).status(status)
                .description(description).source("CAINIAO").occurredAt(Instant.now()).build();
    }

    // ─────────────────────── createShipment ───────────────────────

    @Test
    void createShipment_noOpWhenOrderMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        service.createShipment(id);

        verifyNoInteractions(cainiao);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createShipment_noOpWhenAlreadyHasTracking() {
        Order o = order(OrderStatus.FORWARDED, "CN-EXISTING");
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));

        service.createShipment(o.getId());

        verifyNoInteractions(cainiao);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createShipment_noOpWhenNotForwarded() {
        Order o = order(OrderStatus.PAID, null);
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));

        service.createShipment(o.getId());

        verifyNoInteractions(cainiao);
        verify(orderRepository, never()).save(any());
    }

    @Test
    void createShipment_setsFulfillmentFieldsAndAppendsEvent() {
        Order o = order(OrderStatus.FORWARDED, null);
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(cainiao.createShipments(o)).thenReturn(
                List.of(new FulfillmentResult("Standard Shipping", "CN-TRACK", "LP-REF", 20)));

        service.createShipment(o.getId());

        assertThat(o.getCarrier()).isEqualTo("Standard Shipping");
        assertThat(o.getTrackingNumber()).isEqualTo("CN-TRACK");
        assertThat(o.getFulfillmentRef()).isEqualTo("LP-REF");
        assertThat(o.getTrackingStatus()).isEqualTo(OrderStatus.FORWARDED.name());
        assertThat(o.getEstimatedDeliveryAt()).isNotNull();
        verify(orderRepository).save(o);

        ArgumentCaptor<OrderTrackingEventEntity> ev = ArgumentCaptor.forClass(OrderTrackingEventEntity.class);
        verify(trackingRepository).save(ev.capture());
        assertThat(ev.getValue().getStatus()).isEqualTo(OrderStatus.FORWARDED.name());
        assertThat(ev.getValue().getSource()).isEqualTo("SYSTEM");
    }

    // ─────────────────────── pollEvents ───────────────────────

    @Test
    void pollEvents_returnsNullsWhenOrderMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        TrackingProgress p = service.pollEvents(id);

        assertThat(p.current()).isNull();
        assertThat(p.target()).isNull();
        verifyNoInteractions(cainiao);
    }

    @Test
    void pollEvents_returnsCurrentStatusWhenNoTrackingNumber() {
        Order o = order(OrderStatus.PAID, null);
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));

        TrackingProgress p = service.pollEvents(o.getId());

        assertThat(p.current()).isEqualTo(OrderStatus.PAID);
        assertThat(p.target()).isEqualTo(OrderStatus.PAID);
        verifyNoInteractions(cainiao);
    }

    @Test
    void pollEvents_dedupsAlreadySeenStepsAndPersistsOnlyNewOnes() {
        Order o = order(OrderStatus.SHIPPED, "CN-TRACK");
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        // Existing timeline already contains the first two steps.
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId())).thenReturn(List.of(
                event("FORWARDED", "Envío registrado"),
                event("SHIPPED", "Recogido por el transportista")));
        when(cainiao.track(eq("CN-TRACK"), any(), eq("ES"))).thenReturn(new TrackingSnapshot(
                OrderStatus.SHIPPED, List.of(
                        new TrackingStep(OrderStatus.FORWARDED, "Envío registrado", "Shenzhen, CN", Instant.now()),
                        new TrackingStep(OrderStatus.SHIPPED, "Recogido por el transportista", "Shenzhen, CN", Instant.now()),
                        new TrackingStep(OrderStatus.SHIPPED, "En tránsito internacional", "Hub", Instant.now()))));

        TrackingProgress p = service.pollEvents(o.getId());

        assertThat(p.current()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(p.target()).isEqualTo(OrderStatus.SHIPPED);
        // Only the one genuinely new step is appended.
        verify(trackingRepository, times(1)).save(any());
        assertThat(o.getTrackingStatus()).isEqualTo(OrderStatus.SHIPPED.name());
        verify(orderRepository).save(o);
    }

    @Test
    void pollEvents_notifiesIntermediateShippedButNotTheFirstShipped() {
        UUID userId = UUID.randomUUID();
        Order o = order(OrderStatus.SHIPPED, "CN-TRACK");
        o.setUserId(userId);
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        // Empty timeline → the first SHIPPED step is "Recogido" (covered by shipped()), not notified here.
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId())).thenReturn(List.of());
        when(cainiao.track(eq("CN-TRACK"), any(), eq("ES"))).thenReturn(new TrackingSnapshot(
                OrderStatus.SHIPPED, List.of(
                        new TrackingStep(OrderStatus.SHIPPED, "Recogido por el transportista", "Shenzhen, CN", Instant.now()),
                        new TrackingStep(OrderStatus.SHIPPED, "En tránsito internacional", "Hub", Instant.now()))));
        when(userRepository.getById(userId))
                .thenReturn(User.builder().id(userId).email("buyer@nx.local").language("es").build());

        service.pollEvents(o.getId());

        // Two new events appended, but only the intermediate SHIPPED triggers an email.
        verify(trackingRepository, times(2)).save(any());
        verify(orderEmailService, times(1)).trackingUpdate(eq(o), eq("buyer@nx.local"), eq("es"),
                eq("En tránsito internacional"), anyString());
    }

    @Test
    void pollEvents_doesNotNotifyWhenOrderHasNoUser() {
        Order o = order(OrderStatus.SHIPPED, "CN-TRACK");
        // userId stays null
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(o.getId())).thenReturn(List.of(
                event("SHIPPED", "Recogido por el transportista")));
        when(cainiao.track(eq("CN-TRACK"), any(), eq("ES"))).thenReturn(new TrackingSnapshot(
                OrderStatus.SHIPPED, List.of(
                        new TrackingStep(OrderStatus.SHIPPED, "En reparto", "Local", Instant.now()))));

        service.pollEvents(o.getId());

        verifyNoInteractions(userRepository, orderEmailService);
    }

    // ─────────────────────── ownership view ───────────────────────

    @Test
    void myTrackingView_throwsNotFoundWhenOrderBelongsToAnotherUser() {
        Order o = order(OrderStatus.SHIPPED, "CN-TRACK");
        o.setUserId(UUID.randomUUID());
        when(orderRepository.findById(o.getId())).thenReturn(Optional.of(o));

        assertThatThrownBy(() -> service.myTrackingView(UUID.randomUUID(), o.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void adminTrackingView_throwsNotFoundWhenOrderMissing() {
        UUID id = UUID.randomUUID();
        when(orderRepository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.adminTrackingView(id)).isInstanceOf(NotFoundException.class);
    }

}
