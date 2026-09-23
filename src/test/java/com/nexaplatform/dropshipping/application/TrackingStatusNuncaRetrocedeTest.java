package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingStep;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressFulfillmentService;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentItemRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nexaplatform.dropshipping.config.FulfillmentTestUtil.unSoloTransportista;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * El estado del envío que ve el comprador no desanda el camino.
 *
 * <p>Los dos retrocesos que se fijan aquí se vieron de verdad certificando YunExpress. Un pedido ya
 * marcado ENTREGADO por un push volvía a "en tránsito" en cuanto corría el sondeo periódico, porque
 * éste escribía lo que dijera la API del proveedor en ese instante —y la API tarda en consolidar el
 * último evento respecto al push—. Y dentro de un mismo push el estado quedaba en el ÚLTIMO evento del
 * array, que no tiene por qué ser el más avanzado: YunExpress los agrupa por nodo, no por hora.
 *
 * <p>Un pedido que retrocede es de las cosas que más desconfianza generan en quien está esperando su
 * paquete, y además vuelve a disparar avisos ya enviados.
 */
@DisplayName("El seguimiento sólo avanza")
class TrackingStatusNuncaRetrocedeTest {

    private OrderRepository orderRepository;
    private YunExpressFulfillmentService provider;
    private FulfillmentService service;
    private Order order;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        provider = mock(YunExpressFulfillmentService.class);
        service = new FulfillmentService(orderRepository, mock(OrderTrackingEventRepository.class),
                unSoloTransportista(provider), mock(UserRepository.class), mock(NotificationsPublisher.class),
                mock(OrderEmailService.class), new ObjectMapper(), mock(YunExpressEventCipher.class),
                mock(OpsAlertService.class), mock(NotificationUseCase.class), mock(OrderShipmentRepository.class),
                mock(OrderShipmentItemRepository.class), mock(TrackingViewMapper.class), readyPurchases());

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-TRK-1");
        order.setStatus(OrderStatus.SHIPPED);
        order.setShippingCountry("ES");
        order.setTrackingNumber("YT-1");
    }

    private static TrackingStep step(OrderStatus status, String description) {
        return new TrackingStep(status, description, "Madrid, ES", Instant.parse("2026-07-10T12:00:00Z"));
    }

    private void pushLlega(OrderStatus resumen, TrackingStep... steps) {
        when(orderRepository.findByTrackingNumber("YT-1")).thenReturn(Optional.of(order));
        when(provider.toSnapshot(any(JsonNode.class), anyString()))
                .thenReturn(new TrackingSnapshot(resumen, List.of(steps)));
        service.applyYunExpressPush("{\"waybill_number\":\"YT-1\",\"track_events\":[{}]}");
    }

    @Test
    void elSondeoNoDevuelveAEnTransitoUnPedidoYaEntregado() {
        // Exactamente lo observado: el push adelanta a ENTREGADO y el sondeo posterior, que todavía lee
        // un estado más atrasado en la API del proveedor, lo pisaba.
        order.setTrackingStatus(OrderStatus.DELIVERED.name());
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
        when(provider.track(anyString(), any(), anyString()))
                .thenReturn(new TrackingSnapshot(OrderStatus.FORWARDED, List.of()));

        service.pollEvents(order.getId());

        assertThat(order.getTrackingStatus()).isEqualTo(OrderStatus.DELIVERED.name());
    }

    @Test
    void enUnPushDesordenadoGanaElEventoMasAvanzado() {
        // El array trae primero la entrega y después la salida del almacén. Sin guardia, el pedido se
        // quedaba anunciando la salida pese a estar ya entregado.
        pushLlega(OrderStatus.DELIVERED, step(OrderStatus.DELIVERED, "Entregado al destinatario"),
                step(OrderStatus.SHIPPED, "Salida del almacén de origen"));

        assertThat(order.getTrackingStatus()).isEqualTo(OrderStatus.DELIVERED.name());
    }

    @Test
    void elAvanceNormalSiSeAplica() {
        // La guardia no puede congelar el seguimiento: lo que avanza tiene que avanzar.
        order.setTrackingStatus(OrderStatus.FORWARDED.name());

        pushLlega(OrderStatus.SHIPPED, step(OrderStatus.SHIPPED, "Recogido por el transportista"));

        assertThat(order.getTrackingStatus()).isEqualTo(OrderStatus.SHIPPED.name());
    }

    @Test
    void unDesenlaceFueraDeEscalaSiPisaAlEstadoAvanzado() {
        // CANCELADO no es un paso atrás sino otro final. Perderlo sería peor que perder el orden: el
        // pedido se quedaría anunciando una entrega que no va a ocurrir.
        order.setTrackingStatus(OrderStatus.DELIVERED.name());

        pushLlega(OrderStatus.CANCELLED, step(OrderStatus.CANCELLED, "Envío cancelado en origen"));

        assertThat(order.getTrackingStatus()).isEqualTo(OrderStatus.CANCELLED.name());
    }

    @Test
    void unEstadoGuardadoQueYaNoExisteNoBloqueaElSeguimiento() {
        // Si en base de datos quedó un valor de una versión anterior del enum, el seguimiento debe
        // seguir funcionando en vez de dejar de actualizarse para siempre.
        order.setTrackingStatus("UN_ESTADO_QUE_YA_NO_EXISTE");

        pushLlega(OrderStatus.SHIPPED, step(OrderStatus.SHIPPED, "En tránsito"));

        assertThat(order.getTrackingStatus()).isEqualTo(OrderStatus.SHIPPED.name());
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
