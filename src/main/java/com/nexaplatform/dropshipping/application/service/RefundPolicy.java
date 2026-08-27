package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.enums.OrderStatus;
import com.nexaplatform.dropshipping.domain.model.Order;

/**
 * Cuánto se devuelve al cancelar o reembolsar un pedido.
 *
 * <p>El arancel fijo de la UE lo cobra el transportista al dar entrada al paquete en su almacén y
 * <b>no lo reintegra por ningún motivo</b>: «无论基于何种原因导致包裹在海外派送失败、包裹被退回等情况,
 * 我司已代收的临时固定关税均不予退还». Devolver el 100 % después de eso significa poner ese dinero de
 * nuestro bolsillo.
 *
 * <p>Quién lo asume depende de por qué se devuelve, y así está escrito en las condiciones:
 *
 * <ul>
 *   <li><b>Desistimiento</b> — el artículo 13 de la Directiva 2011/83/UE obliga a reembolsar todos los
 *       pagos recibidos, incluidos los gastos de entrega. Retener ahí el arancel sería probablemente
 *       una cláusula abusiva, así que <b>lo asume el comercio</b>.</li>
 *   <li><b>Causa imputable al cliente</b> —rechaza el paquete, da una dirección incorrecta, la entrega
 *       falla por su parte— <b>el arancel se descuenta</b>, porque ya está pagado y no se recupera.</li>
 * </ul>
 *
 * <p>Y antes de que el paquete salga no hay nada pagado: se devuelve íntegro en los dos casos.
 */
public final class RefundPolicy {

    private RefundPolicy() {
    }

    /** Por qué se devuelve el pedido. Determina quién asume el arancel ya pagado. */
    public enum Reason {
        /** Derecho de desistimiento del consumidor: se reembolsa todo, la ley manda. */
        WITHDRAWAL,
        /** Rechazo, dirección incorrecta o entrega fallida por parte del cliente. */
        CUSTOMER_FAULT
    }

    /** Importe a devolver en céntimos, ya descontado lo que el transportista no reintegra. */
    public static int refundableCents(Order order, Reason reason) {
        if (order == null) {
            return 0;
        }
        int total = Math.max(0, order.getTotalCents());
        if (reason == Reason.WITHDRAWAL || !alreadyDispatched(order)) {
            return total;
        }
        // Nunca convertir un reembolso en un cargo: si el arancel supera al total, se devuelve 0.
        return Math.max(0, total - Math.max(0, order.getCustomsDutyCents()));
    }

    /** ¿Ha entrado ya el paquete en el almacén del transportista? A partir de ahí el arancel está pagado. */
    private static boolean alreadyDispatched(Order order) {
        OrderStatus s = order.getStatus();
        return s == OrderStatus.FORWARDED || s == OrderStatus.SHIPPED || s == OrderStatus.DELIVERED;
    }
}
