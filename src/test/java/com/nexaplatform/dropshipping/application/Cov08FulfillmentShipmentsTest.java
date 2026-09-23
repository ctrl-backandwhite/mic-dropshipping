package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.ShipmentTrackingView;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.TrackingProgress;
import com.nexaplatform.dropshipping.application.service.FulfillmentService.TrackingView;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.EnvioParcialException;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.FulfillmentResult;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingStep;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentItemEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentItemRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nexaplatform.dropshipping.config.FulfillmentTestUtil.unSoloTransportista;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
    private OrderShipmentItemRepository shipmentItemRepository;
    private TrackingViewMapper trackingViewMapper;
    private FulfillmentService service;

    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        trackingRepository = mock(OrderTrackingEventRepository.class);
        provider = mock(FulfillmentProvider.class);
        shipmentRepository = mock(OrderShipmentRepository.class);
        shipmentItemRepository = mock(OrderShipmentItemRepository.class);
        trackingViewMapper = mock(TrackingViewMapper.class);
        service = new FulfillmentService(orderRepository, trackingRepository, unSoloTransportista(provider),
                mock(UserRepository.class), mock(NotificationsPublisher.class), mock(OrderEmailService.class),
                new ObjectMapper(), mock(YunExpressEventCipher.class), mock(OpsAlertService.class),
                mock(NotificationUseCase.class), shipmentRepository, shipmentItemRepository, trackingViewMapper,
                readyPurchases());

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-BULTOS-1");
        order.setStatus(OrderStatus.FORWARDED);
        order.setShippingCountry("ES");
        order.setForwardedAt(Instant.parse("2026-07-01T08:00:00Z"));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    /**
     * Una declaración como la que arma el transportista al crear la guía: a quién va el paquete y qué
     * lleva, con su partida arancelaria.
     */
    private static FulfillmentProvider.ShipmentDeclaration declaracion() {
        return new FulfillmentProvider.ShipmentDeclaration(
                new FulfillmentProvider.DeclaredReceiver("Ana", "López", "ES", "Zaragoza", "Zaragoza",
                        List.of("Calle Mayor 1"), "50001", "+34600000000", "ana@example.com"),
                List.of(new FulfillmentProvider.DeclaredLine("Cotton T-shirt", "棉T恤", "610910", 2,
                        new BigDecimal("15.00"), "USD", new BigDecimal("0.400"), "cotton", "daily use", "SKU-1",
                        "https://detail.1688.com/offer/1.html")));
    }

    /** La misma declaración tal como queda archivada en la columna jsonb del bulto. */
    private static Map<String, Object> declaracionArchivada() {
        return new ObjectMapper().convertValue(declaracion(), new TypeReference<Map<String, Object>>() {
        });
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
        when(provider.createShipments(order))
                .thenReturn(List.of(new FulfillmentResult("YunExpress", "YT-1", "WB-1", 15, 1, 500, 1200, "CH01"),
                        new FulfillmentResult("YunExpress", "YT-2", "WB-2", 15, 2, 700, 900, "CH01")));

        service.createShipment(order.getId());

        ArgumentCaptor<OrderShipmentEntity> saved = ArgumentCaptor.forClass(OrderShipmentEntity.class);
        verify(shipmentRepository, times(2)).save(saved.capture());
        assertThat(saved.getAllValues()).extracting(OrderShipmentEntity::getSequenceNo).containsExactly(1, 2);
        assertThat(saved.getAllValues()).extracting(OrderShipmentEntity::getWaybillNumber).containsExactly("WB-1",
                "WB-2");
        assertThat(saved.getAllValues()).extracting(OrderShipmentEntity::getWeightGrams).containsExactly(500, 700);
        assertThat(saved.getAllValues()).extracting(OrderShipmentEntity::getDeclaredValueCents).containsExactly(1200,
                900);
    }

    @Test
    void elPedidoConservaLosDatosDelPrimerBultoParaListadosYCorreos() {
        // Los listados, el email y la factura siguen enseñando UNA guía: la del primer bulto. El detalle
        // paquete a paquete vive aparte, así que si esto cambiara, el cliente vería una guía distinta de
        // la que le llegó por correo.
        when(provider.createShipments(order))
                .thenReturn(List.of(new FulfillmentResult("YunExpress", "YT-1", "WB-1", 15, 1, 500, 1200, "CH01"),
                        new FulfillmentResult("YunExpress", "YT-2", "WB-2", 15, 2, 700, 900, "CH01")));

        service.createShipment(order.getId());

        assertThat(order.getTrackingNumber()).isEqualTo("YT-1");
        assertThat(order.getFulfillmentRef()).isEqualTo("WB-1");
        assertThat(order.getCarrier()).isEqualTo("YunExpress");
    }

    /**
     * Si un bulto falla, las guías de los anteriores NO se pierden.
     *
     * <p>Un pedido que supera el tope del canal se reparte en varios bultos y se emite una guía por cada
     * uno. Cuando el segundo se topaba con un error, la excepción se llevaba por delante el primero: la
     * etiqueta existía y estaba pagada en el transportista, pero no se guardaba en ninguna parte —ni en el
     * pedido, ni en {@code order_shipment}, ni en el registro—. El javadoc del adaptador decía que «las
     * guías ya creadas se pueden anular desde el panel»; no se podía, porque el panel no las veía.
     *
     * <p>Y el reintento lo remataba: el número de cliente es determinista, así que el transportista
     * rechazaba el segundo intento del bulto 1 por duplicado, lo que se clasifica como fallo PERMANENTE y
     * manda el pedido a la bandeja de incidencias sin vuelta atrás.
     */
    @Test
    void siUnBultoFallaLasGuiasYaEmitidasSeGuardanIgual() {
        FulfillmentResult primero = new FulfillmentResult("YunExpress", "YT-1", "WB-1", 15, 1, 500, 1200, "CH01");
        when(provider.createShipments(order)).thenThrow(
                new EnvioParcialException(List.of(primero), new IllegalStateException("502 del transportista")));

        service.createShipment(order.getId());

        ArgumentCaptor<OrderShipmentEntity> guardado = ArgumentCaptor.forClass(OrderShipmentEntity.class);
        verify(shipmentRepository).save(guardado.capture());
        assertThat(guardado.getValue().getWaybillNumber()).isEqualTo("WB-1");
        // El pedido NO queda despachado: le falta un bulto y tiene que verlo un humano.
        assertThat(order.getFulfillmentAttempts()).isEqualTo(1);
        assertThat(order.getTrackingNumber()).isNull();
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
                .thenReturn(List.of(OrderTrackingEventEntity.builder().orderId(order.getId()).shipmentId(uno.getId())
                        .status(OrderStatus.SHIPPED.name()).description("En tránsito").build()));

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
        verify(trackingViewMapper, never()).toShipmentView(any(), anyList(), anyList());
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
        when(trackingViewMapper.toShipmentView(any(), anyList(), anyList())).thenReturn(
                new ShipmentTrackingView(1, "YunExpress", "YT-1", "SHIPPED", 500, null, List.of(), List.of()));

        TrackingView view = service.adminTrackingView(order.getId());

        assertThat(view.shipments()).hasSize(2);
        verify(trackingViewMapper, times(2)).toShipmentView(any(), anyList(), anyList());
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

    /* ─────────────────── contenido del bulto y aviso por paquete ─────────────────── */

    /**
     * El seguimiento enseña qué va dentro de cada paquete.
     *
     * <p>Sin esto, «Paquete 1/2» no decía nada: quien recibía uno no sabía a qué le estaba siguiendo la
     * pista. El reparto ya se calculaba al crear los envíos, pero se tiraba.
     */
    @Test
    void cadaBultoEnsenaLoQueLleva() {
        order.setStatus(OrderStatus.SHIPPED);
        UUID lineaId = UUID.randomUUID();
        OrderItem linea = OrderItem.builder().productId(UUID.randomUUID()).quantity(3).titleSnapshot("Chaqueta")
                .imageUrlSnapshot("http://img/1.jpg").variantName("Caño 872 / M").build();
        linea.setId(lineaId);
        order.setItems(List.of(linea));
        OrderShipmentEntity uno = shipment(1, "WB-1", "YT-1");
        OrderShipmentEntity dos = shipment(2, "WB-2", "YT-2");
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(uno, dos));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of());
        when(trackingViewMapper.toEventViews(anyList())).thenReturn(List.of());
        when(shipmentItemRepository.findByShipmentIdIn(anyCollection())).thenReturn(List.of(
                OrderShipmentItemEntity.builder().shipmentId(uno.getId()).orderItemId(lineaId).quantity(2).build()));

        ArgumentCaptor<List<FulfillmentService.ParcelItemView>> captor = ArgumentCaptor.forClass(List.class);
        when(trackingViewMapper.toShipmentView(any(), anyList(), captor.capture())).thenReturn(
                new ShipmentTrackingView(1, "YunExpress", "YT-1", "SHIPPED", 500, null, List.of(), List.of()));

        service.adminTrackingView(order.getId());

        List<FulfillmentService.ParcelItemView> delPrimero = captor.getAllValues().get(0);
        assertThat(delPrimero).hasSize(1);
        assertThat(delPrimero.get(0).title()).isEqualTo("Chaqueta");
        assertThat(delPrimero.get(0).imageUrl()).isEqualTo("http://img/1.jpg");
        // Dos de las tres unidades de la línea: el resto viaja en el otro bulto.
        assertThat(delPrimero.get(0).quantity()).isEqualTo(2);
        assertThat(captor.getAllValues().get(1)).as("el segundo bulto no tiene contenido registrado").isEmpty();
    }

    /**
     * Un envío anterior a que se guardara el reparto no rompe el seguimiento.
     *
     * <p>Se pinta sin fotos, como siempre, en vez de fallar: es un seguimiento menos rico, no un error.
     */
    @Test
    void unBultoSinContenidoRegistradoSePintaIgual() {
        order.setStatus(OrderStatus.SHIPPED);
        order.setItems(List.of());
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId()))
                .thenReturn(List.of(shipment(1, "WB-1", "YT-1"), shipment(2, "WB-2", "YT-2")));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of());
        when(trackingViewMapper.toEventViews(anyList())).thenReturn(List.of());
        when(shipmentItemRepository.findByShipmentIdIn(anyCollection())).thenReturn(List.of());
        when(trackingViewMapper.toShipmentView(any(), anyList(), anyList())).thenReturn(
                new ShipmentTrackingView(1, "YunExpress", "YT-1", "SHIPPED", 500, null, List.of(), List.of()));

        TrackingView view = service.adminTrackingView(order.getId());

        assertThat(view.shipments()).hasSize(2);
    }

    /* ─────────────────── declaración enviada al transportista ─────────────────── */

    /**
     * La declaración transmitida se archiva junto al bulto.
     *
     * <p>Del alta de la guía solo se guardaba el resultado —guía, canal, peso y valor—, así que cuando
     * aduana o el transportista rechazaban un envío no había manera de comprobar qué se había declarado
     * sin entrar al panel del transportista, donde no queda histórico propio.
     */
    @Test
    void laDeclaracionEnviadaAlTransportistaSeArchivaConElBulto() {
        when(provider.createShipments(order)).thenReturn(List.of(new FulfillmentResult("YunExpress", "YT-1", "WB-1", 15,
                1, 500, 1200, "CH01", List.of(), declaracion())));

        service.createShipment(order.getId());

        ArgumentCaptor<OrderShipmentEntity> saved = ArgumentCaptor.forClass(OrderShipmentEntity.class);
        verify(shipmentRepository).save(saved.capture());
        Map<String, Object> archivada = saved.getValue().getDeclaration();
        assertThat(archivada).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> destinatario = (Map<String, Object>) archivada.get("receiver");
        assertThat(destinatario).containsEntry("city", "Zaragoza").containsEntry("countryCode", "ES")
                .containsEntry("postalCode", "50001");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lineas = (List<Map<String, Object>>) archivada.get("lines");
        assertThat(lineas).singleElement().satisfies(l -> {
            assertThat(l).containsEntry("hsCode", "610910").containsEntry("quantity", 2);
            assertThat(l).containsEntry("nameLocal", "棉T恤");
        });
    }

    /**
     * Un transportista que no aporte declaración no puede impedir el despacho.
     *
     * <p>La guía ya está emitida y pagada: perder el envío por no poder archivar una copia sería mucho
     * peor que quedarse sin la copia.
     */
    @Test
    void unEnvioSinDeclaracionSeDaDeAltaIgual() {
        when(provider.createShipments(order))
                .thenReturn(List.of(new FulfillmentResult("YunExpress", "YT-1", "WB-1", 15)));

        service.createShipment(order.getId());

        ArgumentCaptor<OrderShipmentEntity> saved = ArgumentCaptor.forClass(OrderShipmentEntity.class);
        verify(shipmentRepository).save(saved.capture());
        assertThat(saved.getValue().getDeclaration()).isNull();
        assertThat(order.getTrackingNumber()).isEqualTo("YT-1");
    }

    /** La ficha del pedido enseña, por cada bulto, lo que se le declaró al transportista. */
    @Test
    void laFichaDelAdminEnsenaLoQueSeLeDeclaroAlTransportista() {
        order.setStatus(OrderStatus.SHIPPED);
        OrderShipmentEntity bulto = shipment(1, "WB-1", "YT-1");
        bulto.setDeclaration(declaracionArchivada());
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(bulto));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of());
        when(trackingViewMapper.toEventViews(anyList())).thenReturn(List.of());

        TrackingView view = service.adminTrackingView(order.getId());

        assertThat(view.declarations()).singleElement().satisfies(d -> {
            assertThat(d.sequenceNo()).isEqualTo(1);
            assertThat(d.waybillNumber()).isEqualTo("WB-1");
            assertThat(d.declaration().receiver().city()).isEqualTo("Zaragoza");
            assertThat(d.declaration().receiver().addressLines()).containsExactly("Calle Mayor 1");
            assertThat(d.declaration().lines()).singleElement().extracting(FulfillmentProvider.DeclaredLine::hsCode)
                    .isEqualTo("610910");
        });
    }

    /**
     * Un envío anterior a este cambio no tiene declaración archivada y la ficha se abre igual.
     *
     * <p>Es justo cuando algo va mal cuando el admin necesita poder abrir el pedido: fallar aquí sería
     * dejarle sin la pantalla en el peor momento.
     */
    @Test
    void unEnvioSinDeclaracionArchivadaNoRompeLaFichaDelPedido() {
        order.setStatus(OrderStatus.SHIPPED);
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId()))
                .thenReturn(List.of(shipment(1, "WB-1", "YT-1")));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of());
        when(trackingViewMapper.toEventViews(anyList())).thenReturn(List.of());

        TrackingView view = service.adminTrackingView(order.getId());

        assertThat(view.declarations()).isEmpty();
    }

    /**
     * La declaración NO viaja en el seguimiento del comprador.
     *
     * <p>Lleva partidas arancelarias, valores declarados, la referencia del proveedor y la URL de origen
     * del artículo: es información del negocio, no del pedido de quien compra.
     */
    @Test
    void laDeclaracionNoSaleEnElSeguimientoDelComprador() {
        UUID comprador = UUID.randomUUID();
        order.setUserId(comprador);
        order.setStatus(OrderStatus.SHIPPED);
        OrderShipmentEntity bulto = shipment(1, "WB-1", "YT-1");
        bulto.setDeclaration(declaracionArchivada());
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(bulto));
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId())).thenReturn(List.of());
        when(trackingViewMapper.toEventViews(anyList())).thenReturn(List.of());

        TrackingView view = service.myTrackingView(comprador, order.getId());

        assertThat(view.declarations()).isEmpty();
    }
}
