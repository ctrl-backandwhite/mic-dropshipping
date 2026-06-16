package com.nexaplatform.dropshipping.infrastructure.integration.fulfillment;

import com.nexaplatform.dropshipping.application.service.FulfillmentService;
import com.nexaplatform.dropshipping.application.usecase.OrderUseCase;
import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Seguimiento <b>semiautomático</b> del envío con Cainiao. Periódicamente:
 * <ol>
 *   <li>crea el envío en Cainiao para los pedidos despachados (FORWARDED) que aún no tienen tracking,</li>
 *   <li>sondea el tracking y, según el estado del transportista, avanza el pedido
 *       FORWARDED→SHIPPED→DELIVERED <b>a través de las transiciones del use case</b> (para que se
 *       disparen los emails «en camino»/«entregado» y los webhooks).</li>
 * </ol>
 * El admin puede forzar la sincronización de un pedido o cambiar el estado a mano en cualquier momento.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FulfillmentSyncScheduler {

    private final OrderRepository orderRepository;
    private final FulfillmentService fulfillmentService;
    private final OrderUseCase orderUseCase;

    @Value("${nexadrop.cainiao.sync-enabled:true}")
    private boolean enabled;

    @Scheduled(fixedDelayString = "${nexadrop.cainiao.sync-interval-ms:60000}")
    public void run() {
        if (!enabled) {
            return;
        }
        for (UUID id : fulfillmentService.activeOrderIds()) {
            try {
                syncOrderById(id);
            } catch (RuntimeException e) {
                log.warn("Cainiao sync falló para pedido {}: {}", id, e.getMessage());
            }
        }
    }

    /**
     * Sincroniza un pedido por id: crea el envío si falta, sondea eventos y avanza el estado
     * semiautomáticamente vía las transiciones del use case (para disparar emails/webhooks). Cada paso se
     * hace en su propia transacción (el use case re-busca el pedido), evitando colecciones desligadas.
     */
    public void syncOrderById(UUID orderId) {
        fulfillmentService.createShipment(orderId); // no-op si ya tiene envío o no está despachado
        FulfillmentService.TrackingProgress p = fulfillmentService.pollEvents(orderId);
        OrderStatus cur = p.current();
        OrderStatus target = p.target();
        if (cur == OrderStatus.FORWARDED && (target == OrderStatus.SHIPPED || target == OrderStatus.DELIVERED)) {
            orderUseCase.shipOrder(orderId);
            cur = OrderStatus.SHIPPED;
        }
        if (cur == OrderStatus.SHIPPED && target == OrderStatus.DELIVERED) {
            orderUseCase.deliverOrder(orderId);
        }
    }
}
