package com.nexaplatform.dropshipping.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.api.mapper.TrackingViewMapper;
import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.FulfillmentProviderSelector;
import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.service.OpsAlertService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.SupplierPurchaseService;
import com.nexaplatform.dropshipping.application.usecase.NotificationUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentItemRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La guía se le pide a quien cobró el porte.
 *
 * <p>Hasta ahora se le pedía siempre al transportista primario, porque solo había uno. Con dos, el pedido
 * guarda cuál eligió el cliente, y despachar por el otro no da error: se cobra un porte y se paga otro, y
 * el paquete sale por quien nadie eligió. Estas pruebas fijan las cuatro situaciones que pueden darse.
 */
class DespachoPorElTransportistaElegidoTest {

    private OrderRepository orderRepository;
    private FulfillmentProvider yunExpress;
    private FulfillmentProvider segundo;
    private Order pedido;

    @BeforeEach
    void prepararEscenario() {
        orderRepository = mock(OrderRepository.class);
        yunExpress = transportista("YUNEXPRESS");
        segundo = transportista("SEGUNDO");
        pedido = new Order();
        pedido.setId(UUID.randomUUID());
        pedido.setOrderNumber("NX-1787000000-0001");
        pedido.setStatus(OrderStatus.FORWARDED);
        when(orderRepository.findById(pedido.getId())).thenReturn(Optional.of(pedido));
        when(orderRepository.save(any(Order.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void unPedidoCobradoPorCjSeDespachaPorCj() {
        pedido.setShippingCarrier("SEGUNDO");

        servicio().createShipment(pedido.getId());

        verify(segundo).createShipments(pedido);
        verify(yunExpress, never()).createShipments(any(Order.class));
    }

    @Test
    void unPedidoSinTransportistaAnotadoSeDespachaPorElPrimario() {
        // Los pedidos anteriores a que existiera la columna: todos eran de YunExpress.
        pedido.setShippingCarrier(null);

        servicio().createShipment(pedido.getId());

        verify(yunExpress).createShipments(pedido);
        verify(segundo, never()).createShipments(any(Order.class));
    }

    @Test
    void siElTransportistaDelPedidoNoEstaDisponibleNoSeDespachaPorOtro() {
        // SEGUNDO apagado en producción, o un valor que ya no existe. Sustituirlo sería pagar un porte
        // distinto del cobrado, así que no se despacha y alguien tiene que mirarlo.
        pedido.setShippingCarrier("DHL");

        servicio().createShipment(pedido.getId());

        verify(yunExpress, never()).createShipments(any(Order.class));
        verify(segundo, never()).createShipments(any(Order.class));
        assertThat(pedido.getFulfillmentError()).contains("DHL");
        assertThat(pedido.getFulfillmentFailedAt()).isNotNull();
    }

    @Test
    void siElTransportistaAunNoPuedeEmitirLaGuiaSeEsperaSinDejarloPorFallido() {
        // SEGUNDO todavía no tiene la mercancía dada de alta: es «todavía no», no un error. Si esto se
        // registrara como fallo, la bandeja de incidencias se llenaría de pedidos que solo esperan.
        pedido.setShippingCarrier("SEGUNDO");
        when(segundo.readyToShip(pedido)).thenReturn(false);

        servicio().createShipment(pedido.getId());

        verify(segundo, never()).createShipments(any(Order.class));
        assertThat(pedido.getFulfillmentError()).isNull();
        assertThat(pedido.getFulfillmentFailedAt()).isNull();
    }

    @Test
    void elSeguimientoSeLePreguntaAlTransportistaDelPedido() {
        // Preguntarle a YunExpress por una guía de SEGUNDO no da error: contesta que no sabe nada, y el
        // cliente se queda mirando un seguimiento que no avanza nunca.
        pedido.setShippingCarrier("SEGUNDO");
        pedido.setTrackingNumber("SEGUNDO-123456789");
        when(segundo.track(any(), any(), any())).thenReturn(new TrackingSnapshot(OrderStatus.SHIPPED, List.of()));

        servicio().pollEvents(pedido.getId());

        verify(segundo).track("SEGUNDO-123456789", pedido.getForwardedAt(), pedido.getShippingCountry());
        verify(yunExpress, never()).track(any(), any(), any());
    }

    private FulfillmentService servicio() {
        FulfillmentProviderSelector selector =
                new FulfillmentProviderSelector(List.of(yunExpress, segundo), yunExpress);
        return new FulfillmentService(orderRepository, mock(OrderTrackingEventRepository.class), selector,
                mock(UserRepository.class), mock(NotificationsPublisher.class), mock(OrderEmailService.class),
                new ObjectMapper(), new YunExpressEventCipher(), mock(OpsAlertService.class),
                mock(NotificationUseCase.class), mock(OrderShipmentRepository.class),
                mock(OrderShipmentItemRepository.class), mock(TrackingViewMapper.class), comprasListas());
    }

    private static FulfillmentProvider transportista(String nombre) {
        FulfillmentProvider transportista = mock(FulfillmentProvider.class);
        lenient().when(transportista.nombre()).thenReturn(nombre);
        lenient().when(transportista.readyToShip(any(Order.class))).thenReturn(true);
        lenient().when(transportista.createShipments(any(Order.class))).thenReturn(List.of());
        return transportista;
    }

    /** La mercancía ya va camino del almacén: sin ese permiso, createShipment se para antes. */
    private static SupplierPurchaseService comprasListas() {
        SupplierPurchaseService compras = mock(SupplierPurchaseService.class);
        lenient().when(compras.readyForInternationalShipment(any())).thenReturn(true);
        return compras;
    }
}
