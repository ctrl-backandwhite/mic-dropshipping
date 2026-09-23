package com.nexaplatform.dropshipping.application.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.infrastructure.integration.chat.ChatToolSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Los pedidos de quien pregunta: estado, envío y seguimiento.
 *
 * <p>
 * <b>El usuario sale del contexto de seguridad, nunca de los argumentos.</b> El
 * modelo no recibe ninguna forma de pedir «el pedido NX-1234» a secas: se le
 * dan los pedidos de la sesión y punto. Si el identificador viniera en los
 * argumentos, bastaría con que alguien escribiera el número de otra persona
 * —o con que el modelo se lo inventara— para leer un pedido ajeno.
 */
@Component
@RequiredArgsConstructor
public class OrdersTool implements ChatTool {

    /** Los últimos pedidos: más allá, la conversación deja de ser útil y el contexto se dispara. */
    private static final int MAX_PEDIDOS = 5;

    private final OrderUseCase orders;
    private final ObjectMapper mapper;

    @Override
    public ChatToolSpec spec() {
        return new ChatToolSpec("consultar_mis_pedidos",
                "Devuelve los últimos pedidos de la persona con la que hablas: número, estado, fechas, "
                        + "transportista y número de seguimiento. No admite argumentos: siempre son los "
                        + "pedidos de quien pregunta.",
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
    }

    /** Sin sesión no existe: al modelo ni se le ofrece, así que no puede intentarlo. */
    @Override
    public boolean allowedFor(ChatContext context) {
        return !context.anonymous();
    }

    @Override
    public String execute(JsonNode arguments, ChatContext context) {
        List<Order> mios = orders.listMyOrders(context.userId());
        ObjectNode salida = mapper.createObjectNode();
        ArrayNode items = salida.putArray("pedidos");
        mios.stream().sorted(Comparator.comparing(Order::getPlacedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(MAX_PEDIDOS).forEach(pedido -> items.add(resumir(pedido)));
        return salida.toString();
    }

    private ObjectNode resumir(Order pedido) {
        ObjectNode nodo = mapper.createObjectNode();
        nodo.put("numero", pedido.getOrderNumber());
        nodo.put("estado", pedido.getStatus() != null ? pedido.getStatus().name() : null);
        nodo.put("fecha", pedido.getPlacedAt() != null ? pedido.getPlacedAt().toString() : null);
        nodo.put("enviado", pedido.getShippedAt() != null ? pedido.getShippedAt().toString() : null);
        nodo.put("entregado", pedido.getDeliveredAt() != null ? pedido.getDeliveredAt().toString() : null);
        nodo.put("transportista", pedido.getShippingCarrier());
        nodo.put("seguimiento", pedido.getTrackingNumber());
        nodo.put("estado_del_envio", pedido.getTrackingStatus());
        // El importe que pagó esa persona sí es suyo y puede preguntarlo. Va en la moneda del
        // pedido y ya sumado: aquí no se calcula nada, solo se lee lo que quedó registrado.
        nodo.put("total", pedido.getTotalCents() / 100.0);
        nodo.put("moneda", pedido.getCurrency());
        return nodo;
    }
}
