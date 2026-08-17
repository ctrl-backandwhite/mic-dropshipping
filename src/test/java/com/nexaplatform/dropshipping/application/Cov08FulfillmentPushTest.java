package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingStep;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentItemRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.nexaplatform.dropshipping.domain.model.User;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.eq;

/**
 * Push entrante del transportista (事件管理): el webhook aplica al timeline los eventos que YunExpress
 * empuja, sin esperar al sondeo.
 *
 * <p>Es una entrada NO confiable: el cuerpo puede venir cifrado, con un número que no es de ningún
 * pedido nuestro, repetido o vacío. Lo que se fija aquí es que ninguno de esos casos escriba basura en
 * el seguimiento que ve el cliente, y que un evento legítimo sí llegue.
 */
class Cov08FulfillmentPushTest {

    private OrderRepository orderRepository;
    private OrderTrackingEventRepository trackingRepository;
    private YunExpressFulfillmentService provider;
    private YunExpressEventCipher cipher;
    private OrderEmailService orderEmailService;
    private UserRepository userRepository;
    private FulfillmentService service;

    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        trackingRepository = mock(OrderTrackingEventRepository.class);
        provider = mock(YunExpressFulfillmentService.class);
        cipher = mock(YunExpressEventCipher.class);
        orderEmailService = mock(OrderEmailService.class);
        userRepository = mock(UserRepository.class);
        service = build(provider);

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-PUSH-1");
        order.setStatus(OrderStatus.SHIPPED);
        order.setShippingCountry("ES");
        order.setTrackingNumber("YT-1");
    }

    private FulfillmentService build(FulfillmentProvider activeProvider) {
        return new FulfillmentService(orderRepository, trackingRepository, activeProvider, userRepository,
                mock(NotificationsPublisher.class), orderEmailService, new ObjectMapper(), cipher, mock(OpsAlertService.class),
                mock(NotificationUseCase.class), mock(OrderShipmentRepository.class), mock(OrderShipmentItemRepository.class),
                mock(TrackingViewMapper.class), readyPurchases());
    }

    private void providerReturns(TrackingStep... steps) {
        when(provider.toSnapshot(any(JsonNode.class), anyString()))
                .thenReturn(new TrackingSnapshot(OrderStatus.SHIPPED, List.of(steps)));
    }

    private static TrackingStep step(OrderStatus status, String description) {
        return new TrackingStep(status, description, "Madrid, ES", Instant.parse("2026-07-10T12:00:00Z"));
    }

    @Test
    void unCuerpoQueNoEsJsonSeDescartaSinTocarNada() {
        service.applyYunExpressPush("esto no es json {");

        verify(orderRepository, never()).save(any());
        verify(trackingRepository, never()).save(any());
    }

    @Test
    void elContenidoCifradoSeDescifraAntesDeAplicarlo() {
        // La política de cifrado del console puede estar activada; si no se descifrara, el push válido se
        // perdería y el cliente no vería avanzar su envío hasta el siguiente sondeo.
        when(cipher.decrypt("AAAA")).thenReturn("{\"waybill_number\":\"WB-1\",\"track_events\":[{}]}");
        when(orderRepository.findByTrackingNumber("WB-1")).thenReturn(Optional.of(order));
        providerReturns(step(OrderStatus.SHIPPED, "Llegó al país de destino"));

        service.applyYunExpressPush("{\"encrypt\":\"AAAA\"}");

        verify(cipher).decrypt("AAAA");
        verify(trackingRepository).save(any(OrderTrackingEventEntity.class));
        verify(orderRepository).save(order);
    }

    @Test
    void unEventoDeUnPedidoDesconocidoSeIgnora() {
        service.applyYunExpressPush("{\"waybill_number\":\"WB-DESCONOCIDA\",\"track_events\":[{}]}");

        verify(trackingRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void siElProveedorActivoNoEsYunExpressElPushSeIgnora() {
        // El formato del evento es propio del transportista: interpretarlo con otro proveedor activo
        // metería estados inventados en el timeline.
        FulfillmentService conOtroProveedor = build(mock(FulfillmentProvider.class));
        when(orderRepository.findByTrackingNumber("WB-1")).thenReturn(Optional.of(order));

        conOtroProveedor.applyYunExpressPush("{\"waybill_number\":\"WB-1\",\"track_events\":[{}]}");

        verify(trackingRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void unEventoSinDescripcionNoEntraEnElTimeline() {
        // Una fila vacía en el seguimiento del cliente no informa de nada y ensucia el historial.
        when(orderRepository.findByTrackingNumber("WB-1")).thenReturn(Optional.of(order));
        providerReturns(step(OrderStatus.SHIPPED, "   "));

        service.applyYunExpressPush("{\"waybill_number\":\"WB-1\",\"track_events\":[{}]}");

        verify(trackingRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void unEventoYaRegistradoNoSeDuplica() {
        // El transportista reenvía el mismo evento cuando no recibe confirmación; sin dedup el cliente
        // vería el mismo paso repetido.
        when(orderRepository.findByTrackingNumber("WB-1")).thenReturn(Optional.of(order));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId()))
                .thenReturn(List.of(OrderTrackingEventEntity.builder().orderId(order.getId())
                        .status(OrderStatus.SHIPPED.name()).description("En tránsito").build()));
        providerReturns(step(OrderStatus.SHIPPED, "En tránsito"));

        service.applyYunExpressPush("{\"waybill_number\":\"WB-1\",\"track_events\":[{}]}");

        verify(trackingRepository, never()).save(any());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void elPedidoSeResuelvePorNuestroNumeroDeClienteCuandoLaGuiaNoLoIdentifica() {
        when(orderRepository.findByOrderNumber("NX-PUSH-1")).thenReturn(Optional.of(order));
        providerReturns(step(OrderStatus.DELIVERED, "Entregado"));

        service.applyYunExpressPush("{\"customer_order_number\":\"NX-PUSH-1\",\"track_events\":[{}]}");

        verify(orderRepository).save(order);
    }

    @Test
    void elPedidoSeResuelveTambienPorLaGuiaAnidadaEnTrackInfo() {
        when(orderRepository.findByTrackingNumber("WB-ANIDADA")).thenReturn(Optional.of(order));
        providerReturns(step(OrderStatus.SHIPPED, "En reparto"));

        service.applyYunExpressPush(
                "{\"track_Info\":{\"waybill_number\":\"WB-ANIDADA\",\"track_events\":[{\"a\":1}]}}");

        verify(orderRepository).save(order);
    }

    @Test
    void losEventosAnidadosEnTrackInfoMandanSobreLosDeLaRaiz() {
        // El sobre trae a veces las dos formas; leer la de la raíz cuando la anidada viene rellena
        // aplicaría un histórico vacío y perdería el evento.
        when(orderRepository.findByTrackingNumber("WB-1")).thenReturn(Optional.of(order));
        providerReturns(step(OrderStatus.SHIPPED, "En tránsito"));

        service.applyYunExpressPush("{\"waybill_number\":\"WB-1\",\"track_events\":[],"
                + "\"track_Info\":{\"track_events\":[{\"track_node_code\":\"ANIDADO\"}]}}");

        ArgumentCaptor<JsonNode> eventos = ArgumentCaptor.forClass(JsonNode.class);
        verify(provider).toSnapshot(eventos.capture(), anyString());
        assertThat(eventos.getValue().toString()).contains("ANIDADO");
    }

    @Test
    void elUltimoEventoDelPushQuedaComoEstadoDeSeguimiento() {
        when(orderRepository.findByTrackingNumber("WB-1")).thenReturn(Optional.of(order));
        providerReturns(step(OrderStatus.SHIPPED, "En reparto"), step(OrderStatus.DELIVERED, "Entregado"));

        service.applyYunExpressPush("{\"waybill_number\":\"WB-1\",\"track_events\":[{}]}");

        assertThat(order.getTrackingStatus()).isEqualTo(OrderStatus.DELIVERED.name());
        assertThat(order.getLastTrackedAt()).isNotNull();
    }

    /**
     * Las compras al proveedor ya están en camino: estos tests van del transportista internacional, no
     * del tramo chino, y sin este permiso {@code createShipment} se frena antes de llamar al carrier.
     */
    private static SupplierPurchaseService readyPurchases() {
        SupplierPurchaseService s = mock(SupplierPurchaseService.class);
        lenient().when(s.readyForInternationalShipment(any())).thenReturn(true);
        return s;
    }

    @Test
    void unPasoIntermedioEnTransitoAvisaAlCompradorPorEmail() {
        // El pedido YA tiene un SHIPPED previo ("recogido"), así que el siguiente paso en tránsito es
        // intermedio y debe notificarse. Sin el fix, el push guardaba el paso pero no avisaba, y el
        // sondeo posterior lo veía ya guardado y tampoco avisaba: el cliente no recibía nada.
        when(orderRepository.findByTrackingNumber("YT-1")).thenReturn(Optional.of(order));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of(
                OrderTrackingEventEntity.builder().orderId(order.getId())
                        .status(OrderStatus.SHIPPED.name()).description("Recogido por el transportista").build()));
        UUID userId = UUID.randomUUID();
        order.setUserId(userId);
        when(userRepository.getById(userId)).thenReturn(
                User.builder().id(userId).email("comprador@x.com").language("es").build());
        providerReturns(step(OrderStatus.SHIPPED, "En tránsito hacia el destino"));

        service.applyYunExpressPush("{\"waybill_number\":\"YT-1\",\"track_events\":[{}]}");

        verify(orderEmailService).trackingUpdate(eq(order), eq("comprador@x.com"), eq("es"),
                eq("En tránsito hacia el destino"), anyString());
    }

    @Test
    void elPrimerShippedDelPushNoDuplicaElCorreoDeRecogida() {
        // Primer SHIPPED del envío = "recogido", que ya tiene su propio correo shipped(). El push no debe
        // mandar ADEMÁS un trackingUpdate por él.
        when(orderRepository.findByTrackingNumber("YT-1")).thenReturn(Optional.of(order));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of());
        order.setUserId(UUID.randomUUID());
        providerReturns(step(OrderStatus.SHIPPED, "Recogido por el transportista"));

        service.applyYunExpressPush("{\"waybill_number\":\"YT-1\",\"track_events\":[{}]}");

        verify(orderEmailService, never()).trackingUpdate(any(), anyString(), anyString(), anyString(), anyString());
    }

}
