package com.nexaplatform.dropshipping.application;

import com.nexaplatform.dropshipping.application.notifications.NotificationsPublisher;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import com.nexaplatform.dropshipping.application.service.OperatorCommissionService;
import com.nexaplatform.dropshipping.application.service.OrderEmailService;
import com.nexaplatform.dropshipping.application.service.WebhookDispatcherService;
import com.nexaplatform.dropshipping.application.usecase.impl.OrderUseCaseImpl;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.integration.search.OrderIndexer;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductPriceTierRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El seguimiento no le cuenta al cliente el mismo hito dos veces.
 *
 * <p>Descubierto certificando en local (18-ago-2026): un pedido entregado enseñaba «Entregado al
 * destinatario» DOS veces, con dos segundos de diferencia, y antes «Recogido por el transportista»
 * seguido de «Paquete recogido por el transportista» —el mismo hecho contado con otras palabras—.
 *
 * <p>Cada avance se escribía por partida doble. El transportista informa del hito y el sondeo
 * ({@code FulfillmentSyncScheduler}) hace avanzar el pedido llamando a {@code shipOrder}/
 * {@code deliverOrder}, que son las mismas operaciones del botón del panel; y esas, además de mandar el
 * correo y el webhook, anotan un paso «marcado por una persona» que en este camino no marcó nadie.
 *
 * <p>La anotación manual sigue haciendo falta —hay pedidos que avanzan a mano, sin transportista que
 * informe, y sin ella el cliente ve la barra en «Entregado» y el detalle parado—, así que no se quita:
 * se escribe sólo cuando el hito no está ya contado. Eso arregla de paso el caso del administrador que
 * pulsa «entregado» después de que el transportista lo haya informado.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeguimientoSinPasosRepetidosTest {

    @Mock
    com.nexaplatform.dropshipping.domain.repository.OrderRepository orderRepository;
    @Mock
    OrderTrackingEventRepository trackingRepository;
    @Mock
    WebhookDispatcherService webhooks;
    @Mock
    NotificationsPublisher notificationsPublisher;
    @Mock
    OrderEmailService orderEmailService;
    @Mock
    OperatorCommissionService operatorCommissionService;
    @Mock
    OrderIndexer orderIndexer;
    @Spy
    CustomsDutyLinesService customsDutyLinesService = new CustomsDutyLinesService(null);
    /** Sin escalera de cantidades: estas pruebas miden otra cosa y un tramo la falsearía. */
    @Mock
    ProductPriceTierRepository priceTierRepository;

    @InjectMocks
    private OrderUseCaseImpl subject;

    private static final UUID PEDIDO = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @BeforeEach
    void pedidoEnCamino() {
        Order o = new Order();
        o.setId(PEDIDO);
        o.setOrderNumber("NX-TEST-0001");
        o.setStatus(OrderStatus.SHIPPED);
        o.setShippingCountry("ES");
        when(orderRepository.findById(PEDIDO)).thenReturn(Optional.of(o));
        when(orderRepository.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    /** Lo que el transportista ya dejó dicho en el seguimiento. */
    private void elTransportistaYaInformo(OrderStatus estado, String descripcion) {
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(PEDIDO)).thenReturn(List.of(OrderTrackingEventEntity
                .builder().orderId(PEDIDO).status(estado.name()).description(descripcion).location("ES")
                .source("YUNEXPRESS").occurredAt(Instant.now()).createdAt(Instant.now()).build()));
    }

    private void seguimientoVacio() {
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(PEDIDO)).thenReturn(List.of());
    }

    @Test
    @DisplayName("si el transportista ya informó de la entrega, no se anota otra vez")
    void noSeRepiteLaEntrega() {
        elTransportistaYaInformo(OrderStatus.DELIVERED, "Entregado al destinatario");

        subject.deliverOrder(PEDIDO);

        verify(trackingRepository, never()).save(any());
    }

    @Test
    @DisplayName("un pedido que avanza a mano SÍ deja su paso: sin él el detalle se queda parado")
    void elAvanceManualSigueDejandoConstancia() {
        seguimientoVacio();

        subject.deliverOrder(PEDIDO);

        ArgumentCaptor<OrderTrackingEventEntity> anotado = ArgumentCaptor.forClass(OrderTrackingEventEntity.class);
        verify(trackingRepository).save(anotado.capture());
        assertThat(anotado.getValue().getDescription()).isEqualTo("Entregado al destinatario");
        assertThat(anotado.getValue().getSource()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("el hito se mide por el ESTADO, no por el texto: el transportista lo dice a su manera")
    void bastaConQueElTransportistaHayaContadoElHito() {
        // El texto del transportista no es el nuestro —«Delivered», «Entregado al destinatario», lo que
        // mande su API en cada idioma—, así que comparar descripciones no vale para saber si ya está contado.
        elTransportistaYaInformo(OrderStatus.DELIVERED, "Delivered to recipient");

        subject.deliverOrder(PEDIDO);

        verify(trackingRepository, never()).save(any());
    }

    @Test
    @DisplayName("informar de la recogida no tapa el paso de la entrega: son hitos distintos")
    void unHitoNoSilenciaElSiguiente() {
        elTransportistaYaInformo(OrderStatus.SHIPPED, "Recogido por el transportista");

        subject.deliverOrder(PEDIDO);

        verify(trackingRepository).save(any());
    }
}
