package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.ShipmentTrackingView;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.TrackingProgress;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.TrackingView;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingStep;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Un pedido que no cabe en un paquete viaja en varios bultos, cada uno con su guía y su ritmo.
 *
 * <p>Lo que se fija aquí es lo que hace que el seguimiento de esos pedidos no mienta: que el pedido no
 * se dé por entregado mientras alguno de sus bultos siga en camino, que los eventos de cada guía queden
 * etiquetados con su bulto (si no, se mezclarían en una sola lista imposible de leer) y que un sondeo
 * repetido no duplique el timeline.
 */
class Cov08FulfillmentShipmentsTest {

    private OrderRepository orderRepository;
    private OrderTrackingEventRepository trackingRepository;
    private FulfillmentProvider provider;
    private OrderShipmentRepository shipmentRepository;
    private TrackingViewMapper trackingViewMapper;
    private FulfillmentService service;

    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        trackingRepository = mock(OrderTrackingEventRepository.class);
        provider = mock(FulfillmentProvider.class);
        shipmentRepository = mock(OrderShipmentRepository.class);
        trackingViewMapper = mock(TrackingViewMapper.class);
        service = new FulfillmentService(orderRepository, trackingRepository, provider, mock(UserRepository.class),
                mock(OrderEmailService.class), new ObjectMapper(), mock(YunExpressEventCipher.class),
                mock(OpsAlertService.class), mock(NotificationUseCase.class), shipmentRepository, trackingViewMapper, readyPurchases());

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-BULTOS-1");
        order.setStatus(OrderStatus.FORWARDED);
        order.setShippingCountry("ES");
        order.setForwardedAt(Instant.parse("2026-07-01T08:00:00Z"));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    private static OrderShipmentEntity shipment(int sequenceNo, String waybill, String tracking) {
        OrderShipmentEntity s = OrderShipmentEntity.builder().orderId(UUID.randomUUID()).sequenceNo(sequenceNo)
                .waybillNumber(waybill).trackingNumber(tracking).build();
        s.setId(UUID.randomUUID());
        return s;
    }

    private static TrackingSnapshot snapshot(OrderStatus current, String description) {
        return new TrackingSnapshot(current,
                List.of(new TrackingStep(current, description, "Madrid, ES", Instant.parse("2026-07-05T09:00:00Z"))));
    }

    // ─────────────────────── alta de los bultos ───────────────────────

    @Test
    void cadaBultoSeDaDeAltaConSuGuiaYSuPesoReal() {
        when(provider.createShipments(order)).thenReturn(List.of(
                new FulfillmentResult("YunExpress", "YT-1", "WB-1", 15, 1, 500, 1200, "CH01"),
                new FulfillmentResult("YunExpress", "YT-2", "WB-2", 15, 2, 700, 900, "CH01")));

        service.createShipment(order.getId());

        ArgumentCaptor<OrderShipmentEntity> saved = ArgumentCaptor.forClass(OrderShipmentEntity.class);
        verify(shipmentRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(OrderShipmentEntity::getSequenceNo).containsExactly(1, 2);
        assertThat(saved.getAllValues()).extracting(OrderShipmentEntity::getWaybillNumber)
                .containsExactly("WB-1", "WB-2");
        assertThat(saved.getAllValues()).extracting(OrderShipmentEntity::getWeightGrams).containsExactly(500, 700);
        assertThat(saved.getAllValues()).extracting(OrderShipmentEntity::getDeclaredValueCents)
                .containsExactly(1200, 900);
    }

    @Test
    void elPedidoConservaLosDatosDelPrimerBultoParaListadosYCorreos() {
        // Los listados, el email y la factura siguen enseñando UNA guía: la del primer bulto. El detalle
        // paquete a paquete vive aparte, así que si esto cambiara, el cliente vería una guía distinta de
        // la que le llegó por correo.
        when(provider.createShipments(order)).thenReturn(List.of(
                new FulfillmentResult("YunExpress", "YT-1", "WB-1", 15, 1, 500, 1200, "CH01"),
                new FulfillmentResult("YunExpress", "YT-2", "WB-2", 15, 2, 700, 900, "CH01")));

        service.createShipment(order.getId());

        assertThat(order.getTrackingNumber()).isEqualTo("YT-1");
        assertThat(order.getFulfillmentRef()).isEqualTo("WB-1");
        assertThat(order.getCarrier()).isEqualTo("YunExpress");
    }

    @Test
    void siElTransportistaNoDevuelveNingunEnvioSeAnotaComoFalloTransitorio() {
        // Una respuesta vacía no es un éxito: sin este caso el pedido se quedaría en FORWARDED sin guía y
        // sin rastro del intento.
        when(provider.createShipments(order)).thenReturn(List.of());

        service.createShipment(order.getId());

        assertThat(order.getFulfillmentAttempts()).isEqualTo(1);
        assertThat(order.getFulfillmentError()).contains("no devolvió ningún envío");
        assertThat(order.getFulfillmentFailedAt()).isNull();
        assertThat(order.getTrackingNumber()).isNull();
        verify(shipmentRepository, never()).save(any());
    }

    // ─────────────────────── sondeo con varios bultos ───────────────────────

    @Test
    void elPedidoNoSeDaPorEntregadoMientrasUnBultoSigaEnCamino() {
        order.setTrackingNumber("YT-1");
        order.setStatus(OrderStatus.SHIPPED);
        OrderShipmentEntity uno = shipment(1, "WB-1", "YT-1");
        OrderShipmentEntity dos = shipment(2, "WB-2", "YT-2");
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(uno, dos));
        when(provider.track(eq("WB-1"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.DELIVERED, "Entregado"));
        when(provider.track(eq("WB-2"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.SHIPPED, "En tránsito"));

        TrackingProgress progress = service.pollEvents(order.getId());

        assertThat(progress.target()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(order.getTrackingStatus()).isEqualTo(OrderStatus.SHIPPED.name());
    }

    @Test
    void soloCuandoTodosLosBultosEstanEntregadosLoEstaElPedido() {
        order.setTrackingNumber("YT-1");
        order.setStatus(OrderStatus.SHIPPED);
        OrderShipmentEntity uno = shipment(1, "WB-1", "YT-1");
        OrderShipmentEntity dos = shipment(2, "WB-2", "YT-2");
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(uno, dos));
        when(provider.track(eq("WB-1"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.DELIVERED, "Entregado 1"));
        when(provider.track(eq("WB-2"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.DELIVERED, "Entregado 2"));

        TrackingProgress progress = service.pollEvents(order.getId());

        assertThat(progress.target()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(uno.getStatus()).isEqualTo(OrderStatus.DELIVERED.name());
        assertThat(dos.getStatus()).isEqualTo(OrderStatus.DELIVERED.name());
    }

    @Test
    void seSondeaPorLaGuiaYSoloSeCaeAlNumeroDeSeguimientoSiNoHayGuia() {
        // La guía es como el transportista identifica el envío; el número que ve el cliente puede
        // asignarse más tarde. Consultar por el que no toca devuelve "sin trazabilidad".
        order.setTrackingNumber("YT-1");
        OrderShipmentEntity conGuia = shipment(1, "WB-1", "YT-1");
        OrderShipmentEntity sinGuia = shipment(2, null, "YT-2");
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(conGuia, sinGuia));
        when(provider.track(eq("WB-1"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.DELIVERED, "Entregado"));
        when(provider.track(eq("YT-2"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.DELIVERED, "Entregado"));

        service.pollEvents(order.getId());

        verify(provider).track(eq("WB-1"), any(), eq("ES"));
        verify(provider).track(eq("YT-2"), any(), eq("ES"));
    }

    @Test
    void unBultoSinGuiaNiNumeroDeSeguimientoSeSaltaSinRomperElSondeo() {
        order.setTrackingNumber("YT-1");
        OrderShipmentEntity valido = shipment(1, "WB-1", null);
        OrderShipmentEntity huerfano = shipment(2, null, null);
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(valido, huerfano));
        when(provider.track(eq("WB-1"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.SHIPPED, "En tránsito"));

        service.pollEvents(order.getId());

        verify(provider, times(1)).track(anyString(), any(), anyString());
    }

    @Test
    void losEventosDeCadaBultoQuedanEtiquetadosConSuBulto() {
        order.setTrackingNumber("YT-1");
        OrderShipmentEntity uno = shipment(1, "WB-1", "YT-1");
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(uno));
        when(provider.track(eq("WB-1"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.SHIPPED, "En tránsito"));

        service.pollEvents(order.getId());

        ArgumentCaptor<OrderTrackingEventEntity> saved = ArgumentCaptor.forClass(OrderTrackingEventEntity.class);
        verify(trackingRepository).save(saved.capture());
        assertThat(saved.getValue().getShipmentId()).isEqualTo(uno.getId());
        assertThat(saved.getValue().getOrderId()).isEqualTo(order.getId());
        assertThat(saved.getValue().getSource()).isEqualTo("YUNEXPRESS");
    }

    @Test
    void unSondeoRepetidoNoDuplicaLosEventosDeUnBulto() {
        order.setTrackingNumber("YT-1");
        OrderShipmentEntity uno = shipment(1, "WB-1", "YT-1");
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(uno));
        when(provider.track(eq("WB-1"), any(), eq("ES"))).thenReturn(snapshot(OrderStatus.SHIPPED, "En tránsito"));
        when(trackingRepository.findByShipmentIdOrderByOccurredAtAsc(uno.getId()))
                .thenReturn(List.of(OrderTrackingEventEntity.builder().orderId(order.getId())
                        .shipmentId(uno.getId()).status(OrderStatus.SHIPPED.name()).description("En tránsito")
                        .build()));

        service.pollEvents(order.getId());

        verify(trackingRepository, never()).save(any());
    }

    @Test
    void conBultosElEventoSeGuardaUnaSolaVezYNoTambienEnElTimelineGlobal() {
        // Si además se guardara desde el timeline global, cada paso saldría dos veces en el seguimiento.
        order.setTrackingNumber("YT-1");
        OrderShipmentEntity uno = shipment(1, "WB-1", "YT-1");
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(uno));
        when(provider.track(eq("WB-1"), any(), eq("ES"))).thenReturn(new TrackingSnapshot(OrderStatus.SHIPPED,
                List.of(new TrackingStep(OrderStatus.SHIPPED, "Salió del almacén", "Shenzhen", Instant.now()),
                        new TrackingStep(OrderStatus.SHIPPED, "En tránsito", "Madrid", Instant.now()))));

        service.pollEvents(order.getId());

        verify(trackingRepository, times(2)).save(any());
    }

    // ─────────────────────── vistas ───────────────────────

    @Test
    void unPedidoDeUnSoloPaqueteNoMuestraDesglosePorBulto() {
        // "Paquete 1 de 1" no aporta nada: la lista global ya lo dice todo.
        order.setStatus(OrderStatus.SHIPPED);
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of());
        when(trackingViewMapper.toEventViews(anyList())).thenReturn(List.of());
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId()))
                .thenReturn(List.of(shipment(1, "WB-1", "YT-1")));

        TrackingView view = service.adminTrackingView(order.getId());

        assertThat(view.shipments()).isEmpty();
        verify(trackingViewMapper, never()).toShipmentView(any(), anyList());
    }

    @Test
    void conVariosBultosCadaPaqueteSaleConSusPropiosEventos() {
        order.setStatus(OrderStatus.SHIPPED);
        OrderShipmentEntity uno = shipment(1, "WB-1", "YT-1");
        OrderShipmentEntity dos = shipment(2, "WB-2", "YT-2");
        OrderTrackingEventEntity deUno = OrderTrackingEventEntity.builder().orderId(order.getId())
                .shipmentId(uno.getId()).status("SHIPPED").description("En tránsito").build();
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of(deUno));
        when(trackingViewMapper.toEventViews(anyList())).thenReturn(List.of());
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(uno, dos));
        when(trackingViewMapper.toShipmentView(any(), anyList()))
                .thenReturn(new ShipmentTrackingView(1, "YunExpress", "YT-1", "SHIPPED", 500, null, List.of()));

        TrackingView view = service.adminTrackingView(order.getId());

        assertThat(view.shipments()).hasSize(2);
        verify(trackingViewMapper, times(2)).toShipmentView(any(), anyList());
    }

    // ─────────────────────── consultas del panel ───────────────────────

    @Test
    void soloSeSondeanLosPedidosConEnvioEnCurso() {
        // Sondear un pedido cancelado o aún sin pagar sería llamar al transportista para nada.
        Order despachado = new Order();
        despachado.setId(UUID.randomUUID());
        despachado.setStatus(OrderStatus.FORWARDED);
        Order enCamino = new Order();
        enCamino.setId(UUID.randomUUID());
        enCamino.setStatus(OrderStatus.SHIPPED);
        Order pagado = new Order();
        pagado.setId(UUID.randomUUID());
        pagado.setStatus(OrderStatus.PAID);
        Order entregado = new Order();
        entregado.setId(UUID.randomUUID());
        entregado.setStatus(OrderStatus.DELIVERED);
        when(orderRepository.findAll()).thenReturn(List.of(despachado, enCamino, pagado, entregado));

        List<UUID> activos = service.activeOrderIds();

        assertThat(activos).containsExactly(despachado.getId(), enCamino.getId());
    }

    @Test
    void laBandejaDeIncidenciasSoloTraeEnviosAbandonadosSinGuiaYLosMasRecientesPrimero() {
        Order antiguo = new Order();
        antiguo.setId(UUID.randomUUID());
        antiguo.setFulfillmentFailedAt(Instant.parse("2026-07-01T00:00:00Z"));
        Order reciente = new Order();
        reciente.setId(UUID.randomUUID());
        reciente.setFulfillmentFailedAt(Instant.parse("2026-07-20T00:00:00Z"));
        Order yaResuelto = new Order();
        yaResuelto.setId(UUID.randomUUID());
        yaResuelto.setFulfillmentFailedAt(Instant.parse("2026-07-10T00:00:00Z"));
        yaResuelto.setTrackingNumber("YT-OK"); // acabó teniendo guía: ya no es incidencia
        Order sano = new Order();
        sano.setId(UUID.randomUUID());
        when(orderRepository.findAll()).thenReturn(List.of(antiguo, reciente, yaResuelto, sano));

        List<Order> fallidos = service.failedFulfillments();

        assertThat(fallidos).extracting(Order::getId).containsExactly(reciente.getId(), antiguo.getId());
    }

    @Test
    void elTimelineYLosBultosSeLeenEnOrden() {
        UUID orderId = order.getId();
        OrderTrackingEventEntity evento = OrderTrackingEventEntity.builder().orderId(orderId).status("SHIPPED")
                .description("En tránsito").build();
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(orderId)).thenReturn(List.of(evento));
        OrderShipmentEntity bulto = shipment(1, "WB-1", "YT-1");
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(orderId)).thenReturn(List.of(bulto));

        assertThat(service.timeline(orderId)).containsExactly(evento);
        assertThat(service.shipmentsOf(orderId)).containsExactly(bulto);
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
}
