package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import com.nexaplatform.dropshipping.domain.model.OrderItem;
import com.nexaplatform.dropshipping.infrastructure.persistence.repository.ProductVariantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gestiona el stock de las VARIANTES a lo largo del ciclo de vida de la venta (modelo "opción 2"):
 * <ul>
 *   <li><b>Descuento</b>: al concretarse la venta (pago confirmado → orden PAID) se resta la cantidad
 *       comprada del stock de cada variante. El descuento es atómico ({@code stock >= qty}) para evitar
 *       sobreventa por carreras. Como en ese punto el cobro externo YA capturó el dinero, si por una
 *       carrera el stock se quedó corto NO se rechaza el pago: se fuerza el stock a 0 y se avisa (nunca
 *       queda negativo).</li>
 *   <li><b>Reintegro</b>: si la venta NO se concreta (cancelación o reembolso) se devuelve la cantidad
 *       al stock de la variante.</li>
 * </ul>
 * Los ítems sin variante ({@code variantId == null}) no afectan al stock (no hay variante que descontar).
 * Todos los métodos se ejecutan dentro de la transacción del llamador (cambio de estado de la orden),
 * por lo que el ajuste de stock y la transición de estado son atómicos.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockService {

    private final ProductVariantRepository variantRepository;

    /** Descuenta del stock la cantidad comprada de cada variante de la orden (venta concretada). */
    public void deductForOrder(Order order) {
        if (order == null || order.getItems() == null) {
            return;
        }
        for (OrderItem item : order.getItems()) {
            if (item.getVariantId() == null || item.getQuantity() <= 0) {
                continue;
            }
            int updated = variantRepository.deductStock(item.getVariantId(), item.getQuantity());
            if (updated == 0) {
                // Sobreventa por carrera: el pago ya se capturó, no se puede rechazar. No dejamos stock
                // negativo → lo forzamos a 0 y avisamos para que administración lo revise/reponga.
                variantRepository.zeroStock(item.getVariantId());
                log.warn("Sobreventa en orden pagada {}: variante {} pedía {} uds sin stock suficiente; "
                        + "stock forzado a 0.", order.getOrderNumber(), item.getVariantId(), item.getQuantity());
            }
        }
    }

    /** Reintegra al stock la cantidad de cada variante de la orden (venta cancelada/reembolsada). */
    public void restoreForOrder(Order order) {
        if (order == null || order.getItems() == null) {
            return;
        }
        for (OrderItem item : order.getItems()) {
            if (item.getVariantId() == null || item.getQuantity() <= 0) {
                continue;
            }
            variantRepository.restoreStock(item.getVariantId(), item.getQuantity());
        }
    }
}
