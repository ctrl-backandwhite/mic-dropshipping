package com.nexaplatform.dropshipping.application;

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
import com.nexaplatform.dropshipping.domain.model.User;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import com.nexaplatform.dropshipping.domain.repository.UserRepository;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingStep;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.YunExpressEventCipher;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderShipmentEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.entity.OrderTrackingEventEntity;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentItemRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderShipmentRepository;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.OrderTrackingEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nexaplatform.dropshipping.config.FulfillmentTestUtil.unSoloTransportista;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El comprador tiene que enterarse de los pasos INTERMEDIOS de su envío: llegada al país, despacho de
 * aduana, salida a reparto. Ninguno de ellos cambia el estado del pedido, así que si el sondeo no avisa
 * de ellos no avisa nadie.
 *
 * <p><b>El fallo que fija esta prueba.</b> El sondeo decidía qué era novedad releyendo el timeline
 * DESPUÉS de que la lectura de cada guía hubiese guardado ya sus eventos, así que todos los pasos salían
 * por «ya conocidos» y no se avisaba de ninguno. Como el reparto en bultos lo usan hoy todos los pedidos,
 * el efecto era total: cero correos de «en tránsito», en silencio y con la suite en verde. Es el mismo
 * fallo que ya se había corregido en el push entrante del transportista.
 *
 * <p><b>Por qué el repositorio de eventos se dobla con memoria y no con respuestas fijas.</b> Lo que
 * rompía el aviso era precisamente que una consulta posterior VE lo que se acaba de guardar —JPA vuelca
 * los INSERT pendientes antes de consultar—. Un doble que devuelva siempre lo mismo no reproduce eso y
 * daría por bueno el código que falla.
 */
class FulfillmentAvisoPasoIntermedioTest {

    private static final String PAIS = "ES";
    private static final String GUIA = "WB-1";
    private static final String CORREO = "compradora@nx036.local";

    /** Textos del transportista, con sus palabras (ver {@code YunExpressTrackNode}). */
    private static final String PASO_RECOGIDA = "Collected by carrier";
    private static final String PASO_LLEGADA = "Arrive at the destination country";
    private static final String PASO_ADUANA = "Clearance processing completed";

    /** El timeline del pedido, con la memoria que tiene la base de datos de verdad. */
    private final List<OrderTrackingEventEntity> timeline = new ArrayList<>();

    private OrderRepository orderRepository;
    private OrderTrackingEventRepository trackingRepository;
    private FulfillmentProvider provider;
    private OrderShipmentRepository shipmentRepository;
    private OrderEmailService orderEmailService;
    private FulfillmentService service;

    private Order order;
    private OrderShipmentEntity bulto;

    @BeforeEach
    void prepararEscenario() {
        orderRepository = mock(OrderRepository.class);
        trackingRepository = mock(OrderTrackingEventRepository.class);
        provider = mock(FulfillmentProvider.class);
        shipmentRepository = mock(OrderShipmentRepository.class);
        orderEmailService = mock(OrderEmailService.class);

        UserRepository userRepository = mock(UserRepository.class);
        SupplierPurchaseService compras = mock(SupplierPurchaseService.class);
        lenient().when(compras.readyForInternationalShipment(any())).thenReturn(true);

        service = new FulfillmentService(orderRepository, trackingRepository, unSoloTransportista(provider), userRepository,
                mock(NotificationsPublisher.class), orderEmailService, new ObjectMapper(),
                mock(YunExpressEventCipher.class), mock(OpsAlertService.class), mock(NotificationUseCase.class),
                shipmentRepository, mock(OrderShipmentItemRepository.class), mock(TrackingViewMapper.class),
                compras);

        order = new Order();
        order.setId(UUID.randomUUID());
        order.setOrderNumber("NX-AVISOS-1");
        order.setStatus(OrderStatus.SHIPPED);
        order.setShippingCountry(PAIS);
        order.setUserId(UUID.randomUUID());
        order.setTrackingNumber("YT-1");
        order.setForwardedAt(Instant.parse("2026-07-01T08:00:00Z"));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        bulto = OrderShipmentEntity.builder().orderId(order.getId()).sequenceNo(1).waybillNumber(GUIA)
                .trackingNumber("YT-1").build();
        bulto.setId(UUID.randomUUID());
        when(shipmentRepository.findByOrderIdOrderBySequenceNoAsc(order.getId())).thenReturn(List.of(bulto));

        when(userRepository.getById(order.getUserId()))
                .thenReturn(User.builder().id(order.getUserId()).email(CORREO).language("es").build());

        doblarTimelineConMemoria();
    }

    @Test
    @DisplayName("cada paso intermedio nuevo del envío le llega al comprador por correo")
    void avisaDeLosPasosIntermediosNuevos() {
        // La primera tanda trae la recogida: ese paso ya lo cuenta el correo de «tu pedido va en camino»,
        // así que aquí no se duplica.
        cuandoElTransportistaCuenta(PASO_RECOGIDA);
        service.pollEvents(order.getId());
        verify(orderEmailService, never()).trackingUpdate(any(), anyString(), anyString(), anyString(), any());

        // La segunda trae la recogida OTRA VEZ —el transportista reenvía siempre todo su historial— y dos
        // pasos nuevos. Solo los nuevos se avisan.
        cuandoElTransportistaCuenta(PASO_RECOGIDA, PASO_LLEGADA, PASO_ADUANA);
        service.pollEvents(order.getId());

        verify(orderEmailService).trackingUpdate(eq(order), eq(CORREO), eq("es"), eq(PASO_LLEGADA), any());
        verify(orderEmailService).trackingUpdate(eq(order), eq(CORREO), eq("es"), eq(PASO_ADUANA), any());
        verify(orderEmailService, times(2))
                .trackingUpdate(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("volver a sondear lo mismo no vuelve a avisar de nada")
    void noRepiteElAvisoDeUnPasoYaContado() {
        cuandoElTransportistaCuenta(PASO_RECOGIDA, PASO_LLEGADA);
        service.pollEvents(order.getId());
        verify(orderEmailService, times(1))
                .trackingUpdate(any(), anyString(), anyString(), anyString(), any());

        service.pollEvents(order.getId());

        verify(orderEmailService, times(1))
                .trackingUpdate(any(), anyString(), anyString(), anyString(), any());
    }

    /** Lo que el transportista responde al consultar la guía, en el orden en que ocurrió. */
    private void cuandoElTransportistaCuenta(String... descripciones) {
        List<TrackingStep> pasos = new ArrayList<>();
        for (int i = 0; i < descripciones.length; i++) {
            pasos.add(new TrackingStep(OrderStatus.SHIPPED, descripciones[i], "Madrid, ES",
                    Instant.parse("2026-07-05T09:00:00Z").plusSeconds(i * 3600L)));
        }
        when(provider.track(eq(GUIA), any(), eq(PAIS)))
                .thenReturn(new TrackingSnapshot(OrderStatus.SHIPPED, pasos));
    }

    /**
     * El doble del repositorio de eventos: lo que se guarda se ve en la siguiente consulta, igual que en
     * la base de datos. Ver la nota de la clase sobre por qué esto es lo que hace útil la prueba.
     */
    private void doblarTimelineConMemoria() {
        when(trackingRepository.save(any())).thenAnswer(invocacion -> {
            OrderTrackingEventEntity evento = invocacion.getArgument(0);
            timeline.add(evento);
            return evento;
        });
        when(trackingRepository.findByOrderIdOrderByOccurredAtAsc(order.getId()))
                .thenAnswer(invocacion -> List.copyOf(timeline));
        when(trackingRepository.findByShipmentIdOrderByOccurredAtAsc(bulto.getId()))
                .thenAnswer(invocacion -> timeline.stream()
                        .filter(e -> bulto.getId().equals(e.getShipmentId())).toList());
    }
}
