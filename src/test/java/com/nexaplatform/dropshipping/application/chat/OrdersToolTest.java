package com.nexaplatform.dropshipping.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrdersToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private OrderUseCase orders;
    private OrdersTool tool;

    private final UUID quienPregunta = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        orders = Mockito.mock(OrderUseCase.class);
        tool = new OrdersTool(orders, mapper);
    }

    private Order pedido(String numero, Instant fecha, OrderStatus estado) {
        Order o = new Order();
        o.setOrderNumber(numero);
        o.setPlacedAt(fecha);
        o.setStatus(estado);
        o.setTotalCents(2990);
        o.setCurrency("EUR");
        o.setTrackingNumber("YT" + numero);
        o.setShippingCarrier("YunExpress");
        return o;
    }

    @Test
    @DisplayName("Sin sesión la herramienta ni siquiera se ofrece al modelo")
    void anonimoNoLaTiene() {
        assertFalse(tool.allowedFor(new ChatContext(null, "es")));
        assertTrue(tool.allowedFor(new ChatContext(quienPregunta, "es")));
    }

    @Test
    @DisplayName("Consulta SIEMPRE por el usuario de la sesión, nunca por lo que diga el modelo")
    void consultaPorElUsuarioDeLaSesion() throws Exception {
        Mockito.when(orders.listMyOrders(Mockito.any())).thenReturn(List.of());
        // El modelo intenta colar el pedido de otra persona en los argumentos.
        JsonNode argumentosMaliciosos = mapper.readTree(
                "{\"userId\":\"00000000-0000-0000-0000-000000000666\",\"numero\":\"NX-DE-OTRO\"}");

        tool.execute(argumentosMaliciosos, new ChatContext(quienPregunta, "es"));

        ArgumentCaptor<UUID> capturado = ArgumentCaptor.forClass(UUID.class);
        Mockito.verify(orders).listMyOrders(capturado.capture());
        assertEquals(quienPregunta, capturado.getValue());
    }

    @Test
    @DisplayName("Devuelve los cinco últimos, del más reciente al más antiguo")
    void devuelveLosUltimosCinco() throws Exception {
        Instant ahora = Instant.parse("2026-08-26T10:00:00Z");
        Mockito.when(orders.listMyOrders(quienPregunta)).thenReturn(List.of(
                pedido("NX-1", ahora.minusSeconds(600), OrderStatus.DELIVERED),
                pedido("NX-2", ahora.minusSeconds(300), OrderStatus.SHIPPED),
                pedido("NX-3", ahora.minusSeconds(100), OrderStatus.PAID),
                pedido("NX-4", ahora.minusSeconds(90), OrderStatus.PAID),
                pedido("NX-5", ahora.minusSeconds(80), OrderStatus.PAID),
                pedido("NX-6", ahora.minusSeconds(70), OrderStatus.PAID)));

        JsonNode salida = mapper.readTree(tool.execute(mapper.createObjectNode(),
                new ChatContext(quienPregunta, "es")));

        assertEquals(5, salida.get("pedidos").size());
        assertEquals("NX-6", salida.get("pedidos").get(0).get("numero").asText());
        assertEquals("NX-2", salida.get("pedidos").get(4).get("numero").asText());
    }

    @Test
    @DisplayName("El resumen lleva seguimiento e importe pagado, que son datos de esa persona")
    void resumenConSeguimiento() throws Exception {
        Mockito.when(orders.listMyOrders(quienPregunta)).thenReturn(List.of(
                pedido("NX-9", Instant.parse("2026-08-20T09:00:00Z"), OrderStatus.SHIPPED)));

        JsonNode salida = mapper.readTree(tool.execute(mapper.createObjectNode(),
                new ChatContext(quienPregunta, "es")));
        JsonNode uno = salida.get("pedidos").get(0);

        assertEquals("YTNX-9", uno.get("seguimiento").asText());
        assertEquals("YunExpress", uno.get("transportista").asText());
        assertEquals("SHIPPED", uno.get("estado").asText());
        assertEquals(29.90, uno.get("total").asDouble(), 0.001);
        assertEquals("EUR", uno.get("moneda").asText());
    }

    @Test
    @DisplayName("Sin pedidos devuelve una lista vacía, no un error")
    void sinPedidos() throws Exception {
        Mockito.when(orders.listMyOrders(quienPregunta)).thenReturn(List.of());

        JsonNode salida = mapper.readTree(tool.execute(mapper.createObjectNode(),
                new ChatContext(quienPregunta, "es")));

        assertTrue(salida.get("pedidos").isEmpty());
    }
}
