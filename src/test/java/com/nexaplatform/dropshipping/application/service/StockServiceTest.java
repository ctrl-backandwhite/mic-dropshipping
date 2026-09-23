package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Modelo de DROPSHIPPING: el stock no se mantiene en la plataforma (lo abastece el proveedor), por lo que
 * ni la venta lo descuenta ni la cancelación lo reintegra. {@link StockService} es intencionadamente un
 * no-op: estos tests fijan ese contrato (nunca toca el inventario, nunca falla).
 */
class StockServiceTest {

    private StockService service() {
        return new StockService();
    }

    private static Order orderWith(OrderItem... items) {
        return Order.builder().orderNumber("ORD-1").items(List.of(items)).build();
    }

    private static OrderItem item(UUID variantId, int qty) {
        return OrderItem.builder().variantId(variantId).quantity(qty).build();
    }

    @Test
    void deductForOrder_isNoOp_stockNeverDepletes() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        // No se descuenta stock en ningún caso: la llamada simplemente no hace nada ni lanza.
        assertThatCode(() -> service().deductForOrder(orderWith(item(v1, 2), item(v2, 5)))).doesNotThrowAnyException();
    }

    @Test
    void restoreForOrder_isNoOp() {
        UUID v = UUID.randomUUID();
        assertThatCode(() -> service().restoreForOrder(orderWith(item(v, 3)))).doesNotThrowAnyException();
    }

    @Test
    void deductAndRestore_tolerateNullOrEmptyOrders() {
        assertThatCode(() -> {
            service().deductForOrder(null);
            service().restoreForOrder(null);
            service().deductForOrder(Order.builder().orderNumber("X").items(null).build());
            service().restoreForOrder(Order.builder().orderNumber("X").items(null).build());
        }).doesNotThrowAnyException();
    }
}
