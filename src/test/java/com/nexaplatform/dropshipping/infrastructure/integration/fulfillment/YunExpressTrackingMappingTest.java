package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.infrastructure.integration.fulfillment.FulfillmentProvider.TrackingSnapshot;
import org.junit.jupiter.api.BeforeEach;
import com.nexaplatform.dropshipping.application.service.CustomsDutyLinesService;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Traducción de los eventos de trazabilidad de YunExpress a la línea temporal del pedido.
 *
 * <p>Lo que se protege aquí es que el estado del pedido salga del ÚLTIMO evento en el tiempo y no del más
 * avanzado de la lista: un paquete devuelto después de un intento de entrega no puede quedarse marcado
 * como entregado, y los eventos no siempre llegan ordenados.
 */
class YunExpressTrackingMappingTest {

    private YunExpressFulfillmentService service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        service = new YunExpressFulfillmentService(null, null, null, new CustomsDutyLinesService(), null, null, null);
        mapper = new ObjectMapper();
    }

    private TrackingSnapshot snapshot(String eventsJson) throws IOException {
        return service.toSnapshot(mapper.readTree(eventsJson), "ES");
    }

    @Test
    void mapeaLosNodosAlEstadoDelPedido() throws IOException {
        String events = """
                [
                  {"track_node_code":"ORDER_CREATION","process_content":"Order created",
                   "process_utc_time":"2026-07-01T10:00:00Z"},
                  {"track_node_code":"MAIN_LINE_DEPART","process_content":"Departure",
                   "process_city":"Shenzhen","process_utc_time":"2026-07-02T10:00:00Z"},
                  {"track_node_code":"DELIVERED","process_content":"Delivered",
                   "process_city":"Madrid","process_utc_time":"2026-07-08T10:00:00Z"}
                ]""";

        TrackingSnapshot snap = snapshot(events);

        assertThat(snap.currentStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(snap.steps()).hasSize(3);
        assertThat(snap.steps().get(0).status()).isEqualTo(OrderStatus.FORWARDED);
        assertThat(snap.steps().get(1).status()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(snap.steps().get(1).location()).isEqualTo("Shenzhen");
        assertThat(snap.steps().get(2).status()).isEqualTo(OrderStatus.DELIVERED);
    }

    @Test
    void ordenaPorFechaYTomaElUltimoEventoComoEstadoActual() throws IOException {
        // Llegan desordenados y la devolución es POSTERIOR a la entrega fallida: el pedido no está entregado.
        String events = """
                [
                  {"track_node_code":"RETURNED","process_content":"Returned",
                   "process_utc_time":"2026-07-10T10:00:00Z"},
                  {"track_node_code":"DELIVERY_FAILURE","process_content":"Delivery failed",
                   "process_utc_time":"2026-07-09T10:00:00Z"}
                ]""";

        TrackingSnapshot snap = snapshot(events);

        assertThat(snap.steps().get(0).description()).isEqualTo("Delivery failed");
        assertThat(snap.steps().get(1).description()).isEqualTo("Returned");
        assertThat(snap.currentStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void unNodoDesconocidoNoRompeElTimeline() throws IOException {
        String events = """
                [{"track_node_code":"NODO_QUE_NO_EXISTE","process_content":"Algo nuevo",
                  "process_utc_time":"2026-07-05T10:00:00Z"}]""";

        TrackingSnapshot snap = snapshot(events);

        assertThat(snap.steps()).hasSize(1);
        assertThat(snap.currentStatus()).isEqualTo(OrderStatus.SHIPPED);
    }

    @Test
    void sinEventosElEnvioSigueRegistrado() throws IOException {
        TrackingSnapshot snap = snapshot("[]");

        assertThat(snap.steps()).isEmpty();
        assertThat(snap.currentStatus()).isEqualTo(OrderStatus.FORWARDED);
    }

    @Test
    void cuandoFaltaLaDescripcionSeUsaLaDelNodoOficial() throws IOException {
        String events = """
                [{"track_node_code":"CUSTOMS_RELEASE","process_content":"",
                  "process_utc_time":"2026-07-05T10:00:00Z"}]""";

        assertThat(snapshot(events).steps().get(0).description()).isEqualTo("Customs Released");
    }

    @Test
    void laTablaDeNodosCubreLosEstadosClave() {
        assertThat(YunExpressTrackNode.from("delivered")).isEqualTo(YunExpressTrackNode.DELIVERED);
        assertThat(YunExpressTrackNode.DELIVERED.status()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(YunExpressTrackNode.ORDER_CREATION.status()).isEqualTo(OrderStatus.FORWARDED);
        assertThat(YunExpressTrackNode.from("")).isNull();
        assertThat(YunExpressTrackNode.from(null)).isNull();
    }
}
