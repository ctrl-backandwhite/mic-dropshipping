package com.nexaplatform.dropshipping.application.service;

import com.nexaplatform.dropshipping.domain.model.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Stock de las VARIANTES en el modelo de DROPSHIPPING: la plataforma NO mantiene inventario propio;
 * el proveedor abastece bajo demanda, por lo que el stock se considera efectivamente ilimitado y
 * <b>no se consume</b> con las ventas. El número de stock guardado en cada variante es solo
 * informativo (rollup del proveedor) y NO debe agotarse al vender ni reponerse al cancelar.
 *
 * <p>Por eso el descuento y el reintegro son intencionadamente <b>no-op</b>: una venta pagada no resta
 * unidades (el stock no se agota) y una cancelación/reembolso no las devuelve. Se conservan los métodos
 * para no tocar a los llamadores (pago, cancelación) y centralizar aquí la política de inventario.
 */
@Slf4j
@Service
public class StockService {

    /**
     * Dropshipping: la venta NO descuenta stock (el inventario lo mantiene el proveedor, es ilimitado).
     * No-op deliberado para que el stock mostrado no se agote con las ventas.
     */
    public void deductForOrder(Order order) {
        // Intencionadamente vacío: en dropshipping el stock no se consume con la venta.
    }

    /**
     * Dropshipping: la cancelación/reembolso NO reintegra stock (nunca se descontó).
     * No-op deliberado, simétrico a {@link #deductForOrder(Order)}.
     */
    public void restoreForOrder(Order order) {
        // Intencionadamente vacío: en dropshipping el stock no se descontó, así que no hay que devolverlo.
    }
}
